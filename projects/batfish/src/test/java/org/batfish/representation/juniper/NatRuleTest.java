package org.batfish.representation.juniper;

import static org.batfish.datamodel.acl.AclLineMatchExprs.match;
import static org.batfish.datamodel.flow.TransformationStep.TransformationType.DEST_NAT;
import static org.batfish.datamodel.transformation.Transformation.when;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.Optional;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.transformation.Noop;
import org.batfish.datamodel.transformation.Transformation.Builder;
import org.batfish.datamodel.transformation.TransformationStep;
import org.batfish.representation.juniper.Nat.Type;
import org.junit.Test;

/** Tests of {@link NatRule}. */
public class NatRuleTest {
  @Test
  public void testToLocation() {
    Nat snat = new Nat(Type.SOURCE);
    Nat dnat = new Nat(Type.DESTINATION);
    NatRule rule = new NatRule("RS");
    Prefix pfx = Prefix.parse("1.1.1.1/32");
    HeaderSpace hs = HeaderSpace.builder().setDstIps(pfx.toIpSpace()).build();
    rule.getMatches().add(new NatRuleMatchDstAddr(pfx));
    rule.setThen(NatRuleThenOff.INSTANCE);

    Ip interfaceIp = Ip.ZERO;
    assertThat(
        rule.toTransformationBuilder(dnat, interfaceIp).map(Builder::build),
        equalTo(Optional.of(when(match(hs)).apply(new Noop(DEST_NAT)).build())));

    rule.setThen(new NatRuleThenPool("pool"));

    // pool is undefined
    assertThat(
        rule.toTransformationBuilder(dnat, interfaceIp).map(Builder::build),
        equalTo(Optional.empty()));

    // pool is defined
    NatPool pool = new NatPool();
    Ip startIp = Ip.parse("5.5.5.5");
    Ip endIp = Ip.parse("6.6.6.6");
    pool.setFromAddress(startIp);
    pool.setToAddress(endIp);
    dnat.getPools().put("pool", pool);

    // destination NAT
    assertThat(
        rule.toTransformationBuilder(dnat, interfaceIp).map(Builder::build),
        equalTo(
            Optional.of(
                when(match(hs))
                    .apply(TransformationStep.assignDestinationIp(startIp, endIp))
                    .build())));

    snat.getPools().put("pool", pool);
    // source NAT
    assertThat(
        rule.toTransformationBuilder(snat, interfaceIp).map(Builder::build),
        equalTo(
            Optional.of(
                when(match(hs))
                    .apply(
                        TransformationStep.assignSourceIp(startIp, endIp),
                        TransformationStep.assignSourcePort(
                            Nat.DEFAULT_FROM_PORT, Nat.DEFAULT_TO_PORT))
                    .build())));
  }
}
