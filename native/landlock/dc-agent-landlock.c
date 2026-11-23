/*
 * dc-agent-landlock — runs a program (the dc-agent) under Landlock + seccomp, so that the kernel, not
 * the program, decides what it may touch. Applied before exec, inherited, never lifted.
 *
 *   dc-agent-landlock --listen-tcp ADDR:PORT [--ro P]... [--rx P]... [--rw P]...
 *                     [--connect-tcp PORT]... [--min-abi N] -- PROGRAM [ARGS...]
 *
 * Fail closed: any error (bad argument, missing path, kernel without the required Landlock ABI, a
 * failing syscall) exits 111 and the program is never started.
 *
 * Landlock: every access right the running kernel knows is handled; only the listed paths get any.
 *   --ro  read files, list directories beneath P
 *   --rx  --ro + execute
 *   --rw  --ro + write, truncate, create regular files and directories, remove, rename (REFER);
 *         never execute, symlinks, devices, fifos, sockets or device ioctls
 *   a file (not a directory) gets the file rights only.
 *   TCP: no bind rule at all; connect only to the --connect-tcp ports (none by default).
 *   Scopes (ABI 6): no signals and no abstract unix sockets outside the sandbox.
 *
 * The listening socket: --listen-tcp opens, binds and listens on ADDR:PORT before the sandbox and
 * hands it over as fd 0 (the agent: WEB_SERVER_INHERITED=true). Afterwards bind() and listen() are
 * refused, so the program can open no listener of its own on any port.
 *
 * seccomp (what Landlock does not cover): socket() only AF_INET/AF_INET6 + SOCK_STREAM + TCP;
 * no socketpair, bind, listen, io_uring, memfd_create, execveat, TCP Fast Open, namespaces
 * (unshare, setns, clone with CLONE_NEW*), ptrace, process_vm_*, bpf, perf_event_open,
 * userfaultfd, keyrings; clone3 answers ENOSYS (glibc falls back to clone); x86_64 only.
 *
 * Descriptors: everything above fd 2 is closed before exec (Landlock does not revoke an open file).
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sched.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define FAIL_EXIT 111
#define MAX_RULES 256
#define MAX_PORTS 64

/* ---- Landlock UAPI, defined here so the build does not depend on the image's kernel headers ---- */

#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif

struct ll_ruleset_attr {
    uint64_t handled_access_fs;
    uint64_t handled_access_net;
    uint64_t scoped;
};

struct ll_path_beneath_attr {
    uint64_t allowed_access;
    int32_t  parent_fd;
} __attribute__((packed));

struct ll_net_port_attr {
    uint64_t allowed_access;
    uint64_t port;
};

#define LL_CREATE_RULESET_VERSION (1U << 0)
#define LL_RULE_PATH_BENEATH      1
#define LL_RULE_NET_PORT          2

#define LL_FS_EXECUTE     (1ULL << 0)
#define LL_FS_WRITE_FILE  (1ULL << 1)
#define LL_FS_READ_FILE   (1ULL << 2)
#define LL_FS_READ_DIR    (1ULL << 3)
#define LL_FS_REMOVE_DIR  (1ULL << 4)
#define LL_FS_REMOVE_FILE (1ULL << 5)
#define LL_FS_MAKE_CHAR   (1ULL << 6)
#define LL_FS_MAKE_DIR    (1ULL << 7)
#define LL_FS_MAKE_REG    (1ULL << 8)
#define LL_FS_MAKE_SOCK   (1ULL << 9)
#define LL_FS_MAKE_FIFO   (1ULL << 10)
#define LL_FS_MAKE_BLOCK  (1ULL << 11)
#define LL_FS_MAKE_SYM    (1ULL << 12)
#define LL_FS_REFER       (1ULL << 13) /* ABI 2 */
#define LL_FS_TRUNCATE    (1ULL << 14) /* ABI 3 */
#define LL_FS_IOCTL_DEV   (1ULL << 15) /* ABI 5 */

#define LL_NET_BIND_TCP    (1ULL << 0) /* ABI 4 */
#define LL_NET_CONNECT_TCP (1ULL << 1) /* ABI 4 */

#define LL_SCOPE_ABSTRACT_UNIX_SOCKET (1ULL << 0) /* ABI 6 */
#define LL_SCOPE_SIGNAL               (1ULL << 1) /* ABI 6 */

#define LL_FS_FILE_RIGHTS (LL_FS_EXECUTE | LL_FS_WRITE_FILE | LL_FS_READ_FILE | LL_FS_TRUNCATE | LL_FS_IOCTL_DEV)

#define RIGHTS_RD (LL_FS_READ_DIR) /* list and open directories beneath; read no file */
#define RIGHTS_RO (LL_FS_READ_FILE | LL_FS_READ_DIR)
#define RIGHTS_RX (RIGHTS_RO | LL_FS_EXECUTE)
#define RIGHTS_RW (RIGHTS_RO | LL_FS_WRITE_FILE | LL_FS_TRUNCATE | LL_FS_MAKE_REG | LL_FS_MAKE_DIR \
                   | LL_FS_REMOVE_FILE | LL_FS_REMOVE_DIR | LL_FS_REFER)

/* every fs right the kernel of this ABI knows — all of them are handled (denied unless granted) */
static uint64_t fs_handled(int abi) {
    uint64_t all = (LL_FS_MAKE_SYM << 1) - 1; /* ABI 1: bits 0..12 */
    if (abi >= 2) all |= LL_FS_REFER;
    if (abi >= 3) all |= LL_FS_TRUNCATE;
    if (abi >= 5) all |= LL_FS_IOCTL_DEV;
    return all;
}

/* ---- arguments ---- */

struct rule {
    const char *path;
    const char *kind; /* "rd" | "ro" | "rx" | "rw" */
    uint64_t    rights;
};

static struct rule rules[MAX_RULES];
static int         rule_count;
static long        connect_ports[MAX_PORTS];
static int         connect_count;

static void fail(const char *aWhat) {
    fprintf(stderr, "dc-agent-landlock: %s — not starting the program\n", aWhat);
    exit(FAIL_EXIT);
}

static void fail_errno(const char *aWhat, const char *aArg) {
    fprintf(stderr, "dc-agent-landlock: %s %s: %s — not starting the program\n", aWhat, aArg ? aArg : "", strerror(errno));
    exit(FAIL_EXIT);
}

static void usage(void) {
    fprintf(stderr,
            "usage: dc-agent-landlock --listen-tcp ADDR:PORT [--rd DIR]... [--ro PATH]... [--rx PATH]... [--rw PATH]...\n"
            "                         [--connect-tcp PORT]... [--min-abi N] -- PROGRAM [ARGS...]\n");
    exit(FAIL_EXIT);
}

static long parse_number(const char *aText, long aMin, long aMax, const char *aWhat) {
    char *end = NULL;
    errno     = 0;
    long v    = strtol(aText, &end, 10);
    if (errno != 0 || end == aText || *end != '\0' || v < aMin || v > aMax) {
        fprintf(stderr, "dc-agent-landlock: bad %s: '%s'\n", aWhat, aText);
        usage();
    }
    return v;
}

static void add_rule(const char *aKind, uint64_t aRights, const char *aPath) {
    if (aPath[0] != '/') {
        fprintf(stderr, "dc-agent-landlock: --%s needs an absolute path: '%s'\n", aKind, aPath);
        usage();
    }
    if (rule_count == MAX_RULES) fail("too many paths");
    rules[rule_count].path   = aPath;
    rules[rule_count].kind   = aKind;
    rules[rule_count].rights = aRights;
    rule_count++;
}

/* ---- the listening socket ---- */

static void open_listener(const char *aSpec) {
    char host[INET6_ADDRSTRLEN + 2];
    const char *colon = strrchr(aSpec, ':');
    if (colon == NULL || (size_t)(colon - aSpec) >= sizeof(host) || colon == aSpec) {
        fprintf(stderr, "dc-agent-landlock: --listen-tcp needs ADDR:PORT ([::1]:PORT for IPv6): '%s'\n", aSpec);
        usage();
    }
    memcpy(host, aSpec, (size_t)(colon - aSpec));
    host[colon - aSpec] = '\0';
    long port = parse_number(colon + 1, 1, 65535, "--listen-tcp port");

    struct sockaddr_storage address;
    socklen_t               length;
    memset(&address, 0, sizeof(address));
    size_t hostLength = strlen(host);
    if (hostLength >= 2 && host[0] == '[' && host[hostLength - 1] == ']') {
        struct sockaddr_in6 *in6 = (struct sockaddr_in6 *)&address;
        host[hostLength - 1]     = '\0';
        if (inet_pton(AF_INET6, host + 1, &in6->sin6_addr) != 1) usage();
        in6->sin6_family = AF_INET6;
        in6->sin6_port   = htons((uint16_t)port);
        length           = sizeof(*in6);
    } else {
        struct sockaddr_in *in4 = (struct sockaddr_in *)&address;
        if (inet_pton(AF_INET, host, &in4->sin_addr) != 1) {
            fprintf(stderr, "dc-agent-landlock: --listen-tcp: not an IPv4 address: '%s'\n", host);
            usage();
        }
        in4->sin_family = AF_INET;
        in4->sin_port   = htons((uint16_t)port);
        length          = sizeof(*in4);
    }

    int fd = socket(address.ss_family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (fd < 0) fail_errno("cannot create the listening socket", aSpec);
    int one = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one)) != 0) fail_errno("SO_REUSEADDR", aSpec);
    if (bind(fd, (struct sockaddr *)&address, length) != 0) fail_errno("cannot bind", aSpec);
    if (listen(fd, 128) != 0) fail_errno("cannot listen on", aSpec);
    /* dup2 clears FD_CLOEXEC on the new descriptor: fd 0 survives exec */
    if (dup2(fd, 0) != 0) fail_errno("cannot move the listening socket to fd 0", aSpec);
    close(fd);
}

/* ---- seccomp ---- */

#define ALLOW         BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
#define DENY(e)       BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ((e) & SECCOMP_RET_DATA))
#define LOAD_NR       BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr))
#define LOAD_ARG(n)   BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[n])) /* low 32 bits */
#define X32_SYSCALL_BIT 0x40000000U
#define NS_FLAGS (CLONE_NEWNS | CLONE_NEWCGROUP | CLONE_NEWUTS | CLONE_NEWIPC | CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET)
#ifndef CLONE_NEWTIME
#define CLONE_NEWTIME 0x00000080
#endif
#ifndef MSG_FASTOPEN
#define MSG_FASTOPEN 0x20000000
#endif
#ifndef TCP_FASTOPEN_CONNECT
#define TCP_FASTOPEN_CONNECT 30
#endif

#ifndef DC_AGENT_LANDLOCK_TEST_NO_SECCOMP
static void install_seccomp(void) {
    /*
     * Layout: a jump table on the syscall number, then one small block per checked syscall.
     * Jumps are relative; each block ends in a return, so blocks never fall into each other.
     * The int arguments (domain, type, protocol, flags, level, optname) are checked on their low
     * 32 bits — the kernel itself truncates them to int.
     */
    struct sock_filter filter[] = {
        /* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        /* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
        /* 2 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        /* 3 */ LOAD_NR,
        /* 4 */ BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, X32_SYSCALL_BIT, 0, 1),
        /* 5 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),

        /* plain refusals */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socketpair, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_bind, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_listen, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_enter, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_register, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_memfd_create, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_unshare, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_setns, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ptrace, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_readv, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_writev, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_bpf, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_perf_event_open, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_userfaultfd, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_add_key, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_keyctl, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_request_key, 0, 1), DENY(EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1), DENY(ENOSYS),

        /* socket(domain, type, protocol): AF_INET/AF_INET6, SOCK_STREAM, protocol 0 or TCP */
        /* +0 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 11),
        /* +1 */ LOAD_ARG(0),
        /* +2 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 1, 0),
        /* +3 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 0, 6),
        /* +4 */ LOAD_ARG(1),
        /* +5 */ BPF_STMT(BPF_ALU | BPF_AND | BPF_K, ~(uint32_t)(SOCK_NONBLOCK | SOCK_CLOEXEC)),
        /* +6 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SOCK_STREAM, 0, 3),
        /* +7 */ LOAD_ARG(2),
        /* +8 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 2, 0),
        /* +9 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, IPPROTO_TCP, 1, 0),
        /* +10 */ DENY(EPERM),
        /* +11 */ ALLOW,

        /* clone(flags, ...): no new namespaces */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4),
        LOAD_ARG(0),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, NS_FLAGS | CLONE_NEWTIME, 0, 1),
        DENY(EPERM),
        ALLOW,

        /* sendto(fd, buf, len, flags, ...) / sendmmsg(fd, vec, n, flags): no MSG_FASTOPEN */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendto, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendmmsg, 0, 4),
        LOAD_ARG(3),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, MSG_FASTOPEN, 0, 1),
        DENY(EPERM),
        ALLOW,

        /* sendmsg(fd, msg, flags): no MSG_FASTOPEN */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_sendmsg, 0, 4),
        LOAD_ARG(2),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, MSG_FASTOPEN, 0, 1),
        DENY(EPERM),
        ALLOW,

        /* setsockopt(fd, level, optname, ...): no TCP_FASTOPEN_CONNECT */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_setsockopt, 0, 6),
        LOAD_ARG(1),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, IPPROTO_TCP, 0, 3),
        LOAD_ARG(2),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, TCP_FASTOPEN_CONNECT, 0, 1),
        DENY(EPERM),
        ALLOW,

        ALLOW,
    };
    struct sock_fprog program = {.len = (unsigned short)(sizeof(filter) / sizeof(filter[0])), .filter = filter};
    /* prctl, not seccomp(2): systemd's @system-service allows prctl, not the seccomp syscall */
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program, 0, 0) != 0) fail_errno("cannot install the seccomp filter", NULL);
}
#endif

/* ---- main ---- */

/* A closed 0, 1 or 2 would be the next descriptor open() returns — the ruleset, then replaced by
 * the listener on fd 0. Put /dev/null there first, so our own descriptors are always >= 3. */
static void normalize_std_fds(void) {
    for (int fd = 0; fd <= 2; fd++) {
        if (fcntl(fd, F_GETFD) != -1 || errno != EBADF) continue;
        int null = open("/dev/null", O_RDWR);
        if (null != fd) {
            fprintf(stderr, "dc-agent-landlock: cannot reopen fd %d on /dev/null — not starting the program\n", fd);
            exit(FAIL_EXIT);
        }
    }
}

int main(int argc, char **argv) {
    normalize_std_fds();
    const char *listen_spec = NULL;
    long        min_abi     = 4;
    int         i           = 1;
    for (; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--") == 0) {
            i++;
            break;
        }
        if (i + 1 >= argc) usage();
        const char *value = argv[++i];
        if (strcmp(a, "--rd") == 0) {
            add_rule("rd", RIGHTS_RD, value);
        } else if (strcmp(a, "--ro") == 0) {
            add_rule("ro", RIGHTS_RO, value);
        } else if (strcmp(a, "--rx") == 0) {
            add_rule("rx", RIGHTS_RX, value);
        } else if (strcmp(a, "--rw") == 0) {
            add_rule("rw", RIGHTS_RW, value);
        } else if (strcmp(a, "--connect-tcp") == 0) {
            if (connect_count == MAX_PORTS) fail("too many ports");
            connect_ports[connect_count++] = parse_number(value, 1, 65535, "--connect-tcp port");
        } else if (strcmp(a, "--listen-tcp") == 0) {
            if (listen_spec != NULL) usage();
            listen_spec = value;
        } else if (strcmp(a, "--min-abi") == 0) {
            min_abi = parse_number(value, 1, 1000, "--min-abi");
        } else {
            fprintf(stderr, "dc-agent-landlock: unknown option '%s'\n", a);
            usage();
        }
    }
    if (i > argc || strcmp(argv[i - 1], "--") != 0) usage();
    if (i >= argc || argv[i][0] != '/') {
        fprintf(stderr, "dc-agent-landlock: PROGRAM after -- must be an absolute path\n");
        usage();
    }
    if (listen_spec == NULL) {
        fprintf(stderr, "dc-agent-landlock: --listen-tcp is required (the program may not open a listener itself)\n");
        usage();
    }
    char **program = &argv[i];

    int abi = (int)syscall(__NR_landlock_create_ruleset, NULL, 0, LL_CREATE_RULESET_VERSION);
    if (abi < 0) fail_errno("Landlock is not available in this kernel", NULL);
    if (abi < min_abi) {
        fprintf(stderr, "dc-agent-landlock: the kernel has Landlock ABI %d, --min-abi is %ld — not starting the program\n", abi, min_abi);
        exit(FAIL_EXIT);
    }
    if (abi < 4) {
        /* without TCP rules a program could connect anywhere: refuse instead of a weaker sandbox */
        fprintf(stderr, "dc-agent-landlock: Landlock ABI %d has no TCP rules (needs 4) — not starting the program\n", abi);
        exit(FAIL_EXIT);
    }

    struct ll_ruleset_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.handled_access_fs  = fs_handled(abi);
    attr.handled_access_net = LL_NET_BIND_TCP | LL_NET_CONNECT_TCP;
    if (abi >= 6) attr.scoped = LL_SCOPE_ABSTRACT_UNIX_SOCKET | LL_SCOPE_SIGNAL;
    int ruleset = (int)syscall(__NR_landlock_create_ruleset, &attr, sizeof(attr), 0);
    if (ruleset < 0) fail_errno("landlock_create_ruleset", NULL);

    fprintf(stderr, "dc-agent-landlock: Landlock ABI %d, fs 0x%llx, net 0x%llx, scoped 0x%llx\n", abi,
            (unsigned long long)attr.handled_access_fs, (unsigned long long)attr.handled_access_net,
            (unsigned long long)attr.scoped);

    for (int r = 0; r < rule_count; r++) {
        int fd = open(rules[r].path, O_PATH | O_CLOEXEC);
        if (fd < 0) fail_errno("cannot open", rules[r].path);
        struct stat st;
        if (fstat(fd, &st) != 0) fail_errno("cannot stat", rules[r].path);
        uint64_t rights = rules[r].rights & attr.handled_access_fs;
        if (!S_ISDIR(st.st_mode)) rights &= LL_FS_FILE_RIGHTS;
        if (rights == 0) { /* --rd on a file: nothing to grant, and a rule without rights is refused */
            fprintf(stderr, "dc-agent-landlock: --%s needs a directory: '%s' — not starting the program\n",
                    rules[r].kind, rules[r].path);
            exit(FAIL_EXIT);
        }
        struct ll_path_beneath_attr beneath = {.allowed_access = rights, .parent_fd = fd};
        if (syscall(__NR_landlock_add_rule, ruleset, LL_RULE_PATH_BENEATH, &beneath, 0) != 0) {
            fail_errno("landlock_add_rule", rules[r].path);
        }
        close(fd);
        fprintf(stderr, "dc-agent-landlock: %s %s (%s, 0x%llx)\n", rules[r].kind, rules[r].path,
                S_ISDIR(st.st_mode) ? "dir" : "file", (unsigned long long)rights);
    }
    for (int p = 0; p < connect_count; p++) {
        struct ll_net_port_attr port = {.allowed_access = LL_NET_CONNECT_TCP, .port = (uint64_t)connect_ports[p]};
        if (syscall(__NR_landlock_add_rule, ruleset, LL_RULE_NET_PORT, &port, 0) != 0) {
            fail_errno("landlock_add_rule (port)", NULL);
        }
        fprintf(stderr, "dc-agent-landlock: connect tcp %ld\n", connect_ports[p]);
    }

    open_listener(listen_spec);
    fprintf(stderr, "dc-agent-landlock: listening on %s (fd 0)\n", listen_spec);

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fail_errno("PR_SET_NO_NEW_PRIVS", NULL);
    if (syscall(__NR_landlock_restrict_self, ruleset, 0) != 0) fail_errno("landlock_restrict_self", NULL);
    close(ruleset);

#ifndef DC_AGENT_LANDLOCK_TEST_NO_SECCOMP
    install_seccomp();
#else
    fprintf(stderr, "dc-agent-landlock: TEST BUILD — no seccomp filter\n");
#endif

    if (close_range(3, ~0U, 0) != 0) fail_errno("close_range", NULL);

    extern char **environ;
    execve(program[0], program, environ);
    fail_errno("cannot exec", program[0]);
    return FAIL_EXIT;
}
