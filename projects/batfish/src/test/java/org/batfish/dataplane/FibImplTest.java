package org.batfish.dataplane;

import static org.batfish.datamodel.matchers.AbstractRouteDecoratorMatchers.hasPrefix;
import static org.batfish.dataplane.ibdp.TestUtils.annotateRoute;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.Fib;
import org.batfish.datamodel.FibImpl;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.InterfaceAddress;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.NetworkFactory;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.Vrf;
import org.batfish.dataplane.rib.Rib;
import org.batfish.main.Batfish;
import org.batfish.main.BatfishTestUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link FibImpl} */
@RunWith(JUnit4.class)
public class FibImplTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  private static final Ip DST_IP = Ip.parse("3.3.3.3");
  private static final String NODE1 = "node1";
  private static final String FAST_ETHERNET_0 = "FastEthernet0/0";
  private static final InterfaceAddress NODE1_PHYSICAL_NETWORK = new InterfaceAddress("2.0.0.1/8");
  private static final Ip EXTERNAL_IP = Ip.parse("7.7.7.7");

  private Interface.Builder _ib;
  private Configuration _config;
  private Vrf _vrf;

  @Before
  public void setup() {
    NetworkFactory nf = new NetworkFactory();
    Configuration.Builder cb =
        nf.configurationBuilder().setConfigurationFormat(ConfigurationFormat.CISCO_IOS);
    _ib = nf.interfaceBuilder();
    _config = cb.setHostname(NODE1).build();
    _vrf = nf.vrfBuilder().setOwner(_config).setName(Configuration.DEFAULT_VRF_NAME).build();
    _ib.setOwner(_config).setVrf(_vrf);
  }

  @Test
  public void testGetNextHopInterfacesByRoute() throws IOException {
    String iface1 = "iface1";
    String iface2 = "iface2";
    String iface3 = "iface3";
    Ip ip1 = Ip.parse("1.1.1.0");
    Ip ip2 = Ip.parse("2.2.2.0");
    _ib.setName(iface1).setAddress(new InterfaceAddress(ip1, 24)).build();
    _ib.setName(iface2).setAddress(new InterfaceAddress(ip2, 24)).build();
    _ib.setName(iface3).setAddress(new InterfaceAddress(ip2, 24)).build();

    Batfish batfish =
        BatfishTestUtils.getBatfish(ImmutableSortedMap.of(_config.getHostname(), _config), folder);
    batfish.computeDataPlane();
    Fib fib =
        batfish
            .loadDataPlane()
            .getFibs()
            .get(_config.getHostname())
            .get(Configuration.DEFAULT_VRF_NAME);

    // Should have one LocalRoute per interface (also one ConnectedRoute, but LocalRoute will have
    // longer prefix match). Should see only iface1 in interfaces to ip1.
    Map<AbstractRoute, Map<String, Map<Ip, Set<AbstractRoute>>>> nextHopIfacesByRouteToIp1 =
        fib.getNextHopInterfacesByRoute(ip1);
    assertThat(nextHopIfacesByRouteToIp1, aMapWithSize(1));
    Set<String> nextHopIfacesToIp1 =
        nextHopIfacesByRouteToIp1.values().stream()
            .flatMap(ifaceMap -> ifaceMap.keySet().stream())
            .collect(Collectors.toSet());
    assertThat(nextHopIfacesToIp1, contains(iface1));

    // Should see interfaces iface2 and iface3 in interfaces to ip2.
    Map<AbstractRoute, Map<String, Map<Ip, Set<AbstractRoute>>>> nextHopIfacesByRouteToIp2 =
        fib.getNextHopInterfacesByRoute(ip2);
    assertThat(nextHopIfacesByRouteToIp2, aMapWithSize(2));
    Set<String> nextHopIfacesToIp2 =
        nextHopIfacesByRouteToIp2.values().stream()
            .flatMap(ifaceMap -> ifaceMap.keySet().stream())
            .collect(Collectors.toSet());
    assertThat(nextHopIfacesToIp2, containsInAnyOrder(iface2, iface3));
  }

  @Test
  public void testNextHopInterfaceTakesPrecedence() throws IOException {
    _ib.setName(FAST_ETHERNET_0).setAddresses(NODE1_PHYSICAL_NETWORK).build();
    /*
     * Both next hop IP and interface on the static route. Interface should take precedence
     * EXTERNAL_IP should be ignored (i.e., not resolved recursively) -- there will not be a route
     * for it.
     */
    _vrf.setStaticRoutes(
        ImmutableSortedSet.of(
            StaticRoute.builder()
                .setNetwork(Prefix.create(DST_IP, 32))
                .setNextHopInterface(FAST_ETHERNET_0)
                .setNextHopIp(EXTERNAL_IP)
                .setAdministrativeCost(1)
                .build()));

    Batfish batfish =
        BatfishTestUtils.getBatfish(ImmutableSortedMap.of(_config.getHostname(), _config), folder);
    batfish.computeDataPlane();
    Fib fib =
        batfish
            .loadDataPlane()
            .getFibs()
            .get(_config.getHostname())
            .get(Configuration.DEFAULT_VRF_NAME);

    assertThat(fib.getNextHopInterfaces(DST_IP), contains(FAST_ETHERNET_0));
  }

  @Test
  public void testNextHopIpIsResolved() throws IOException {
    _ib.setName(FAST_ETHERNET_0).setAddresses(NODE1_PHYSICAL_NETWORK).build();
    /*
     * Only next hop ip on the static route.
     * Next hop IP should be resolved, and match the connected route on FastEthernet0/0
     */
    _vrf.setStaticRoutes(
        ImmutableSortedSet.of(
            StaticRoute.builder()
                .setNetwork(Prefix.create(DST_IP, 32))
                .setNextHopIp(Ip.parse("2.1.1.1"))
                .setAdministrativeCost(1)
                .build()));

    Batfish batfish =
        BatfishTestUtils.getBatfish(ImmutableSortedMap.of(_config.getHostname(), _config), folder);
    batfish.computeDataPlane();
    Fib fib =
        batfish
            .loadDataPlane()
            .getFibs()
            .get(_config.getHostname())
            .get(Configuration.DEFAULT_VRF_NAME);

    assertThat(fib.getNextHopInterfaces(DST_IP), contains(FAST_ETHERNET_0));
  }

  @Test
  public void testNonForwardingRouteNotInFib() {
    Rib rib = new Rib();

    StaticRoute nonForwardingRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.0/24"))
            .setNextHopInterface("Eth1")
            .setAdministrativeCost(1)
            .setNonForwarding(true)
            .build();
    StaticRoute forwardingRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("2.2.2.0/24"))
            .setNextHopInterface("Eth1")
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    rib.mergeRoute(annotateRoute(nonForwardingRoute));
    rib.mergeRoute(annotateRoute(forwardingRoute));

    Fib fib = new FibImpl(rib);
    Set<AbstractRoute> fibRoutes = fib.getRoutesByNextHopInterface().get("Eth1");

    assertThat(fibRoutes, not(hasItem(hasPrefix(Prefix.parse("1.1.1.0/24")))));
    assertThat(fibRoutes, hasItem(hasPrefix(Prefix.parse("2.2.2.0/24"))));
  }

  @Test
  public void testResolutionWhenNextHopMatchesNonForwardingRoute() {
    Rib rib = new Rib();

    StaticRoute nonForwardingRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.1/32"))
            .setNextHopInterface("Eth2")
            .setAdministrativeCost(1)
            .setNonForwarding(true)
            .build();

    StaticRoute forwardingLessSpecificRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.0/31"))
            .setNextHopInterface("Eth1")
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    StaticRoute testRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("2.2.2.0/24"))
            .setNextHopIp(Ip.parse("1.1.1.1")) // matches both routes defined above
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    rib.mergeRoute(annotateRoute(nonForwardingRoute));
    rib.mergeRoute(annotateRoute(forwardingLessSpecificRoute));
    rib.mergeRoute(annotateRoute(testRoute));

    Fib fib = new FibImpl(rib);
    Set<AbstractRoute> fibRoutesEth1 = fib.getRoutesByNextHopInterface().get("Eth1");

    /* 2.2.2.0/24 should resolve to the "forwardingLessSpecificRoute" and thus eth1 */
    assertThat(fibRoutesEth1, hasItem(hasPrefix(Prefix.parse("2.2.2.0/24"))));

    /* Nothing can resolve to "eth2" */
    Set<AbstractRoute> fibRoutesEth2 = fib.getRoutesByNextHopInterface().get("Eth2");
    assertThat(fibRoutesEth2, nullValue());
  }

  @Test
  public void testResolutionWhenNextHopMatchesNonForwardingRouteWithECMP() {
    Rib rib = new Rib();

    StaticRoute nonForwardingRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.1/32"))
            .setNextHopInterface("Eth2")
            .setAdministrativeCost(1)
            .setNonForwarding(true)
            .build();

    StaticRoute ecmpForwardingRoute1 =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.1/32"))
            .setNextHopInterface("Eth3")
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();
    StaticRoute ecmpForwardingRoute2 =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.1/32"))
            .setNextHopInterface("Eth4")
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    StaticRoute forwardingLessSpecificRoute =
        StaticRoute.builder()
            .setNetwork(Prefix.parse("1.1.1.0/31"))
            .setNextHopInterface("Eth1")
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    final Prefix TEST_PREFIX = Prefix.parse("2.2.2.0/24");
    StaticRoute testRoute =
        StaticRoute.builder()
            .setNetwork(TEST_PREFIX)
            .setNextHopIp(Ip.parse("1.1.1.1")) // matches multiple routes defined above
            .setAdministrativeCost(1)
            .setNonForwarding(false)
            .build();

    rib.mergeRoute(annotateRoute(nonForwardingRoute));
    rib.mergeRoute(annotateRoute(forwardingLessSpecificRoute));
    rib.mergeRoute(annotateRoute(testRoute));
    rib.mergeRoute(annotateRoute(ecmpForwardingRoute1));
    rib.mergeRoute(annotateRoute(ecmpForwardingRoute2));

    Fib fib = new FibImpl(rib);

    /* 2.2.2.0/24 should resolve to eth3 and eth4*/
    assertThat(fib.getRoutesByNextHopInterface().get("Eth3"), hasItem(hasPrefix(TEST_PREFIX)));
    assertThat(fib.getRoutesByNextHopInterface().get("Eth4"), hasItem(hasPrefix(TEST_PREFIX)));

    /* 2.2.2.0/24 should NOT resolve to "forwardingLessSpecificRoute" (and thus Eth1)
     * because more specific eth3/4
     */
    assertThat(fib.getRoutesByNextHopInterface().get("Eth1"), not(hasItem(hasPrefix(TEST_PREFIX))));

    /* Nothing can resolve to eth2 */
    Set<AbstractRoute> fibRoutesEth2 = fib.getRoutesByNextHopInterface().get("Eth2");
    assertThat(fibRoutesEth2, nullValue());
  }
}
