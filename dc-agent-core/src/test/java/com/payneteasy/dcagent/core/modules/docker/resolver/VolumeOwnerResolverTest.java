package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.security.TIdRef;
import com.payneteasy.dcagent.core.config.model.docker.security.TIdSource;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import org.junit.Test;

import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_GROUP;
import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class VolumeOwnerResolverTest {

    private static final String           PATH  = "volumes[0].directoryOrCreate.owner";
    private static final TSecurityContext BOTH  = TSecurityContext.builder().runAsUser(1001).runAsGroup(1002).build();
    private static final TVolumeOwner     RUNAS = owner(ref(RUN_AS_USER), ref(RUN_AS_GROUP));

    private final VolumeOwnerResolver resolver = new VolumeOwnerResolver();

    @Test
    public void run_as_with_both_fields() {
        assertThat(resolver.resolve(RUNAS, BOTH, PATH)).isEqualTo(owner(id(1001), id(1002)));
    }

    @Test
    public void run_as_without_group_fails() {
        TSecurityContext userOnly = TSecurityContext.builder().runAsUser(1001).build();

        assertThatThrownBy(() -> resolver.resolve(RUNAS, userOnly, PATH))
                .hasMessageContainingAll(PATH + ".group", "securityContext.runAsGroup", "not set");
    }

    @Test
    public void run_as_without_user_fails() {
        TSecurityContext groupOnly = TSecurityContext.builder().runAsGroup(1002).build();

        assertThatThrownBy(() -> resolver.resolve(RUNAS, groupOnly, PATH))
                .hasMessageContainingAll(PATH + ".user", "securityContext.runAsUser");
    }

    @Test
    public void reference_without_security_context_fails() {
        assertThatThrownBy(() -> resolver.resolve(owner(null, ref(RUN_AS_GROUP)), null, PATH))
                .hasMessageContainingAll(PATH + ".group", "securityContext.runAsGroup");
    }

    @Test
    public void numbers_do_not_need_security_context() {
        assertThat(resolver.resolve(owner(id(0), id(2000)), null, PATH)).isEqualTo(owner(id(0), id(2000)));
    }

    @Test
    public void mixed_number_and_reference() {
        assertThat(resolver.resolve(owner(id(0), ref(RUN_AS_GROUP)), BOTH, PATH)).isEqualTo(owner(id(0), id(1002)));
    }

    @Test
    public void absent_field_stays_absent() {
        assertThat(resolver.resolve(owner(null, id(2000)), BOTH, PATH)).isEqualTo(owner(null, id(2000)));
    }

    @Test
    public void no_owner() {
        assertThat(resolver.resolve(null, BOTH, PATH)).isNull();
    }

    private static TVolumeOwner owner(TIdRef aUser, TIdRef aGroup) {
        return TVolumeOwner.builder().user(aUser).group(aGroup).build();
    }

    private static TIdRef id(int aId) {
        return TIdRef.builder().id(aId).build();
    }

    private static TIdRef ref(TIdSource aSource) {
        return TIdRef.builder().ref(aSource).build();
    }
}
