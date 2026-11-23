/*
 * dc-agent-landlock-probe — test tool, never released. Performs each operation with a direct
 * syscall and prints one line per operation:
 *
 *     <op> <ALLOWED|DENIED errno|VALUE n>
 *
 * Several operations run in one process (a sandbox is applied once per process):
 *     probe write /srv/x  read /etc/os-release  tcp-connect 127.0.0.1 22  ...
 * The test scripts compare the lines with the expected matrix for each layer.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef MSG_FASTOPEN
#define MSG_FASTOPEN 0x20000000
#endif
#ifndef IPPROTO_SCTP
#define IPPROTO_SCTP 132
#endif

static void allowed(const char *aOp) {
    printf("%s ALLOWED\n", aOp);
}

static void denied(const char *aOp, int aErrno) {
    printf("%s DENIED %s\n", aOp, strerrorname_np(aErrno) ? strerrorname_np(aErrno) : "?");
}

static void report(const char *aOp, int aResult) {
    if (aResult == 0) allowed(aOp);
    else denied(aOp, errno);
}

static int write_file(const char *aPath, const void *aData, size_t aLength, mode_t aMode) {
    int fd = open(aPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, aMode);
    if (fd < 0) return -1;
    ssize_t n = write(fd, aData, aLength);
    int saved = errno;
    close(fd);
    errno = saved;
    return n == (ssize_t)aLength ? 0 : -1;
}

static int read_self(char **aData, size_t *aLength) {
    int fd = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0) return -1;
    char *data = malloc((size_t)st.st_size);
    size_t done = 0;
    while (done < (size_t)st.st_size) {
        ssize_t n = read(fd, data + done, (size_t)st.st_size - done);
        if (n <= 0) {
            close(fd);
            return -1;
        }
        done += (size_t)n;
    }
    close(fd);
    *aData   = data;
    *aLength = done;
    return 0;
}

/* fork + exec in the child; the child's exec errno comes back over a CLOEXEC pipe */
static int run_child(int aMemfd, const char *aPath, char *const aArgv[]) {
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        close(pipefd[0]);
        int null = open("/dev/null", O_WRONLY);
        if (null >= 0) dup2(null, 1);
        extern char **environ;
        if (aMemfd >= 0) {
            syscall(SYS_execveat, aMemfd, "", aArgv, environ, AT_EMPTY_PATH);
        } else {
            execve(aPath, aArgv, environ);
        }
        int e = errno;
        if (write(pipefd[1], &e, sizeof(e)) < 0) _exit(126);
        _exit(127);
    }
    close(pipefd[1]);
    int e = 0;
    ssize_t n = read(pipefd[0], &e, sizeof(e));
    close(pipefd[0]);
    int status;
    waitpid(pid, &status, 0);
    if (n == (ssize_t)sizeof(e)) {
        errno = e;
        return -1;
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) return 0;
    errno = ECHILD; /* exec happened, the program itself failed: reported, but not as a sandbox refusal */
    return -1;
}

static int inet_address(const char *aHost, const char *aPort, struct sockaddr_in *aOut) {
    memset(aOut, 0, sizeof(*aOut));
    aOut->sin_family = AF_INET;
    aOut->sin_port   = htons((uint16_t)atoi(aPort));
    return inet_pton(AF_INET, aHost, &aOut->sin_addr) == 1 ? 0 : -1;
}

static int unix_address(const char *aName, int aAbstract, struct sockaddr_un *aOut, socklen_t *aLength) {
    memset(aOut, 0, sizeof(*aOut));
    aOut->sun_family = AF_UNIX;
    size_t n = strlen(aName);
    if (n + 2 > sizeof(aOut->sun_path)) return -1;
    if (aAbstract) {
        memcpy(aOut->sun_path + 1, aName, n); /* sun_path[0] == 0: abstract */
        *aLength = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    } else {
        memcpy(aOut->sun_path, aName, n);
        *aLength = (socklen_t)sizeof(*aOut);
    }
    return 0;
}

/* Landlock UAPI, as in the launcher */
struct ll_ruleset_attr { uint64_t fs, net, scoped; };
struct ll_path_beneath_attr { uint64_t allowed; int32_t fd; } __attribute__((packed));

static void *thread_body(void *aArg) {
    return aArg;
}

static int op(const char *aName, char **aArgs) {
    if (strcmp(aName, "noop") == 0) {
        allowed(aName);
        return 0;
    }
    if (strcmp(aName, "write") == 0) {
        int r = write_file(aArgs[0], "x", 1, 0644);
        report(aName, r);
        if (r == 0) unlink(aArgs[0]);
        return 1;
    }
    if (strcmp(aName, "read") == 0) {
        int fd = open(aArgs[0], O_RDONLY | O_CLOEXEC);
        char c;
        if (fd >= 0 && read(fd, &c, 1) < 0) {
            int e = errno;
            close(fd);
            errno = e;
            fd = -1;
        }
        report(aName, fd >= 0 ? 0 : -1);
        if (fd >= 0) close(fd);
        return 1;
    }
    if (strcmp(aName, "list") == 0) { /* list DIR: open it and read its entries */
        DIR *dir = opendir(aArgs[0]);
        int  r   = dir == NULL ? -1 : 0;
        if (dir != NULL) {
            errno = 0;
            if (readdir(dir) == NULL && errno != 0) r = -1;
            int e = errno;
            closedir(dir);
            errno = e;
        }
        report(aName, r);
        return 1;
    }
    if (strcmp(aName, "fsync-dir") == 0) { /* fsync-dir DIR: open it read-only and fsync, as the agent does */
        int fd = open(aArgs[0], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        int r  = fd < 0 ? -1 : fsync(fd);
        int e  = errno;
        if (fd >= 0) close(fd);
        errno = e;
        report(aName, r);
        return 1;
    }
    if (strcmp(aName, "mkdir") == 0) {
        int r = mkdir(aArgs[0], 0755);
        report(aName, r);
        if (r == 0) rmdir(aArgs[0]);
        return 1;
    }
    if (strcmp(aName, "symlink") == 0) { /* symlink TARGET LINKPATH */
        int r = symlink(aArgs[0], aArgs[1]);
        report(aName, r);
        if (r == 0) unlink(aArgs[1]);
        return 2;
    }
    if (strcmp(aName, "rename-out") == 0) { /* rename-out FILE_IN_RW DEST_OUTSIDE */
        if (write_file(aArgs[0], "x", 1, 0644) != 0) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 2;
        }
        int r = rename(aArgs[0], aArgs[1]);
        report(aName, r);
        unlink(r == 0 ? aArgs[1] : aArgs[0]);
        return 2;
    }
    if (strcmp(aName, "exec") == 0) {
        char *argv[] = {aArgs[0], NULL};
        report(aName, run_child(-1, aArgs[0], argv));
        return 1;
    }
    if (strcmp(aName, "memfd-exec") == 0) {
        char  *data;
        size_t length;
        if (read_self(&data, &length) != 0) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 0;
        }
        int fd = (int)syscall(SYS_memfd_create, "probe", 0);
        if (fd < 0) {
            denied(aName, errno);
            return 0;
        }
        if (write(fd, data, length) != (ssize_t)length) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 0;
        }
        char *argv[] = {"probe", "noop", NULL};
        report(aName, run_child(fd, NULL, argv));
        close(fd);
        free(data);
        return 0;
    }
    if (strcmp(aName, "memfd-mmap-exec") == 0) {
        int fd = (int)syscall(SYS_memfd_create, "probe", 0);
        if (fd < 0) {
            denied(aName, errno);
            return 0;
        }
        if (ftruncate(fd, 4096) != 0) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 0;
        }
        void *p = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
        report(aName, p == MAP_FAILED ? -1 : 0);
        if (p != MAP_FAILED) munmap(p, 4096);
        close(fd);
        return 0;
    }
    if (strcmp(aName, "ldso-exec") == 0) { /* ldso-exec LDSO DIR: copy self into DIR, run it through LDSO */
        char  *data;
        size_t length;
        char   payload[4096];
        snprintf(payload, sizeof(payload), "%s/llprobe-payload", aArgs[1]);
        if (read_self(&data, &length) != 0 || write_file(payload, data, length, 0755) != 0) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 2;
        }
        char *argv[] = {aArgs[0], payload, "noop", NULL};
        report(aName, run_child(-1, aArgs[0], argv));
        unlink(payload);
        free(data);
        return 2;
    }
    if (strcmp(aName, "tcp-connect") == 0 || strcmp(aName, "tcp-fastopen") == 0) { /* HOST PORT */
        struct sockaddr_in address;
        inet_address(aArgs[0], aArgs[1], &address);
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 2;
        }
        int r;
        if (aName[4] == 'f') {
            r = sendto(fd, "x", 1, MSG_FASTOPEN, (struct sockaddr *)&address, sizeof(address)) == 1 ? 0 : -1;
        } else {
            r = connect(fd, (struct sockaddr *)&address, sizeof(address));
        }
        report(aName, r);
        close(fd);
        return 2;
    }
    if (strcmp(aName, "tfo-sendmsg") == 0 || strcmp(aName, "tfo-sendmmsg") == 0
        || strcmp(aName, "tfo-connect-opt") == 0) { /* HOST PORT: the other TCP Fast Open paths */
        struct sockaddr_in address;
        inet_address(aArgs[0], aArgs[1], &address);
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 2;
        }
        int r;
        if (strcmp(aName, "tfo-connect-opt") == 0) {
            int one = 1;
            r = setsockopt(fd, IPPROTO_TCP, 30 /* TCP_FASTOPEN_CONNECT */, &one, sizeof(one));
            if (r == 0) r = connect(fd, (struct sockaddr *)&address, sizeof(address));
        } else {
            struct iovec  iov = {.iov_base = "x", .iov_len = 1};
            struct msghdr msg = {.msg_name = &address, .msg_namelen = sizeof(address), .msg_iov = &iov, .msg_iovlen = 1};
            if (aName[11] == '\0') { /* tfo-sendmsg */
                r = sendmsg(fd, &msg, MSG_FASTOPEN) == 1 ? 0 : -1;
            } else {
                struct mmsghdr one = {.msg_hdr = msg};
                r = sendmmsg(fd, &one, 1, MSG_FASTOPEN) == 1 ? 0 : -1;
            }
        }
        report(aName, r);
        close(fd);
        return 2;
    }
    if (strcmp(aName, "tcp-bind") == 0) { /* PORT on 127.0.0.1 */
        struct sockaddr_in address;
        inet_address("127.0.0.1", aArgs[0], &address);
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 1;
        }
        report(aName, bind(fd, (struct sockaddr *)&address, sizeof(address)));
        close(fd);
        return 1;
    }
    if (strcmp(aName, "listen-autobind") == 0) { /* listen() without bind(), then connect to the port */
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 0;
        }
        if (listen(fd, 1) != 0) {
            denied(aName, errno);
            close(fd);
            return 0;
        }
        struct sockaddr_in address;
        socklen_t          length = sizeof(address);
        getsockname(fd, (struct sockaddr *)&address, &length);
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        int client = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        int r      = client < 0 ? -1 : connect(client, (struct sockaddr *)&address, sizeof(address));
        if (r == 0) printf("%s ALLOWED port %d\n", aName, ntohs(address.sin_port));
        else denied(aName, errno);
        if (client >= 0) close(client);
        close(fd);
        return 0;
    }
    if (strcmp(aName, "sctp-socket") == 0) {
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_SCTP);
        report(aName, fd >= 0 ? 0 : -1);
        if (fd >= 0) close(fd);
        return 0;
    }
    if (strcmp(aName, "udp-send") == 0) { /* HOST PORT */
        struct sockaddr_in address;
        inet_address(aArgs[0], aArgs[1], &address);
        int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 2;
        }
        report(aName, sendto(fd, "probe", 5, 0, (struct sockaddr *)&address, sizeof(address)) == 5 ? 0 : -1);
        close(fd);
        return 2;
    }
    if (strcmp(aName, "unix-connect") == 0 || strcmp(aName, "abstract-connect") == 0) { /* PATH | NAME */
        struct sockaddr_un address;
        socklen_t          length = 0;
        unix_address(aArgs[0], aName[0] == 'a', &address, &length);
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            denied(aName, errno);
            return 1;
        }
        report(aName, connect(fd, (struct sockaddr *)&address, length));
        close(fd);
        return 1;
    }
    if (strcmp(aName, "socketpair") == 0) {
        int sv[2];
        int r = socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
        report(aName, r);
        if (r == 0) {
            close(sv[0]);
            close(sv[1]);
        }
        return 0;
    }
    if (strcmp(aName, "kill") == 0) { /* PID: signal 0 */
        report(aName, kill((pid_t)atoi(aArgs[0]), 0));
        return 1;
    }
    if (strcmp(aName, "read-fd") == 0) { /* N: an inherited descriptor */
        char c;
        report(aName, read(atoi(aArgs[0]), &c, 1) >= 0 ? 0 : -1);
        return 1;
    }
    if (strcmp(aName, "nested-ruleset") == 0) { /* PATH: allow everything under / in a new ruleset, then write PATH */
        struct ll_ruleset_attr attr = {.fs = (1ULL << 13) - 1};
        int rs = (int)syscall(444, &attr, sizeof(attr), 0);
        int root = open("/", O_PATH | O_CLOEXEC);
        struct ll_path_beneath_attr beneath = {.allowed = (1ULL << 13) - 1, .fd = root};
        if (rs < 0 || root < 0 || syscall(445, rs, 1, &beneath, 0) != 0
            || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 || syscall(446, rs, 0) != 0) {
            printf("%s SETUP-FAILED %s\n", aName, strerrorname_np(errno));
            return 1;
        }
        int r = write_file(aArgs[0], "x", 1, 0644);
        report(aName, r);
        if (r == 0) unlink(aArgs[0]);
        return 1;
    }
    if (strcmp(aName, "nnp") == 0) {
        printf("%s VALUE %d\n", aName, prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0));
        return 0;
    }
    if (strcmp(aName, "fd0-listening") == 0) {
        int       value  = -1;
        socklen_t length = sizeof(value);
        if (getsockopt(0, SOL_SOCKET, SO_ACCEPTCONN, &value, &length) != 0) denied(aName, errno);
        else printf("%s VALUE %d\n", aName, value);
        return 0;
    }
    if (strcmp(aName, "unshare-user") == 0) {
        report(aName, unshare(CLONE_NEWUSER));
        return 0;
    }
    if (strcmp(aName, "io-uring") == 0) {
        char params[120];
        memset(params, 0, sizeof(params));
        long fd = syscall(SYS_io_uring_setup, 1, params);
        report(aName, fd >= 0 ? 0 : -1);
        if (fd >= 0) close((int)fd);
        return 0;
    }
    if (strcmp(aName, "thread") == 0) {
        pthread_t t;
        int r = pthread_create(&t, NULL, thread_body, NULL);
        if (r == 0) pthread_join(t, NULL);
        errno = r;
        report(aName, r == 0 ? 0 : -1);
        return 0;
    }
    fprintf(stderr, "probe: unknown operation '%s'\n", aName);
    exit(2);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    for (int i = 1; i < argc;) {
        const char *name = argv[i++];
        i += op(name, &argv[i]);
    }
    return 0;
}
