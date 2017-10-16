package org.batfish.symbolic.abstraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.common.Pair;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.OspfNeighbor;
import org.batfish.datamodel.OspfProcess;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.Vrf;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.answers.AbstractionAnswerElement;
import org.batfish.symbolic.utils.PrefixUtils;
import org.batfish.symbolic.utils.Tuple;

/**
 * Creates an abstraction(s) of the network by splitting the network into a collection of
 * equivalence classes and compressing the representation of each equivalence class. Each
 * equivalence class has the property that all of its stable solutions are bisimilar to the original
 * network. That is, there is a bug in the abstracted network iff there is a bug in the concrete
 * network.
 *
 * <p>How the compression occurs does depend on the property we want to check, since we can only
 * check properties for all concrete nodes that map to the same abstract node. For example, if we
 * want to check reachability between two concrete nodes, then these 2 nodes must remain distinct in
 * the compressed form.
 */

// - iBGP check source ACL
// - add parent / client RRs
// - Always assume multipath?

public class Abstraction implements Iterable<EquivalenceClass> {

  private IBatfish _batfish;

  private Graph _graph;

  private BDDNetwork _network;

  private List<Prefix> _slice;

  private Set<String> _concreteNodes;

  private Map<Set<String>, List<Prefix>> _destinationMap;

  private Abstraction(
      IBatfish batfish, @Nullable Set<String> concrete, @Nullable List<Prefix> prefixes) {
    _batfish = batfish;
    _graph = new Graph(batfish);
    _network = BDDNetwork.create(_graph);
    _slice = prefixes;
    _concreteNodes = concrete;
    _destinationMap = new HashMap<>();
  }

  public static Abstraction create(
      IBatfish batfish, @Nullable Set<String> concrete, @Nullable List<Prefix> prefixes) {
    Abstraction abs = new Abstraction(batfish, concrete, prefixes);
    abs.computeDestinationMap();
    return abs;
  }

  public static Abstraction create(IBatfish batfish, @Nullable Set<String> concrete) {
    Abstraction abs = new Abstraction(batfish, concrete, null);
    abs.computeDestinationMap();
    return abs;
  }

  private void computeDestinationMap() {
    Map<String, List<Protocol>> protoMap = buildProtocolMap();

    PrefixTrieMap pt = new PrefixTrieMap();

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      // System.out.println("Looking at router: " + router);
      for (Protocol proto : protoMap.get(router)) {
        List<Prefix> destinations = new ArrayList<>();
        // For connected interfaces add address if there is a peer
        // Otherwise, add the entire prefix since we don't know
        /* if (proto.isConnected()) {
          destinations = new ArrayList<>();
          List<GraphEdge> edges = _graph.getEdgeMap().get(router);
          for (GraphEdge ge : edges) {
            if (ge.getPeer() == null) {
              destinations.add(ge.getStart().getPrefix());
            } else {
              Ip ip = ge.getStart().getPrefix().getAddress();
              Prefix pfx = new Prefix(ip,32);
              destinations.add(pfx);
            }
          }
        } else { */
        if (!proto.isStatic()) {
          destinations = Graph.getOriginatedNetworks(conf, proto);
        }
        //}

        // Add all destinations to the prefix trie relevant to this slice
        for (Prefix p : destinations) {
          if (_slice == null || PrefixUtils.overlap(p, _slice)) {
            pt.add(p, router);
          }
        }
      }
    }

    // Map collections of devices to the destination IP ranges that are rooted there
    _destinationMap = pt.createDestinationMap();

    System.out.println("Destination Map:");
    _destinationMap.forEach(
        (devices, prefixes) -> System.out.println("Devices: " + devices + " --> " + prefixes));
  }


  private Map<String, List<Protocol>> buildProtocolMap() {
    // Figure out which protocols are running on which devices
    Map<String, List<Protocol>> protocols = new HashMap<>();
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      List<Protocol> protos = new ArrayList<>();
      protocols.put(router, protos);

      if (conf.getDefaultVrf().getOspfProcess() != null) {
        protos.add(Protocol.OSPF);
      }

      if (conf.getDefaultVrf().getBgpProcess() != null) {
        protos.add(Protocol.BGP);
      }

      if (!conf.getDefaultVrf().getStaticRoutes().isEmpty()) {
        protos.add(Protocol.STATIC);
      }

      if (!conf.getInterfaces().isEmpty()) {
        protos.add(Protocol.CONNECTED);
      }
    }
    return protocols;
  }

  @Nonnull
  @Override
  public Iterator<EquivalenceClass> iterator() {
    return new AbstractionIterator();
  }

  private EquivalenceClass computeAbstraction(Set<String> devices, List<Prefix> prefixes) {
    Set<String> allDevices = _graph.getConfigurations().keySet();

    Map<GraphEdge, InterfacePolicy> exportPol = new HashMap<>();
    Map<GraphEdge, InterfacePolicy> importPol = new HashMap<>();
    _network.getExportPolicyMap().forEach((ge, pol) -> exportPol.put(ge, pol));
    _network.getImportPolicyMap().forEach((ge, pol) -> importPol.put(ge, pol));

    UnionSplit<String> workset = new UnionSplit<>(allDevices);

    // System.out.println("Workset: " + workset);

    // Add concrete nodes to the set of devices that must be concrete
    if (_concreteNodes != null) {
      devices.addAll(_concreteNodes);
    }

    // Split by the singleton set for each origination point
    for (String device : devices) {
      Set<String> ds = new TreeSet<>();
      ds.add(device);
      workset.split(ds);
    }

    // System.out.println("Computing abstraction for: " + devices);

    // Repeatedly split the abstraction to a fixed point
    Set<Set<String>> todo;
    do {
      todo = new HashSet<>();
      List<Set<String>> ps = workset.partitions();

      // System.out.println("Todo set: " + todo);
      // System.out.println("Workset: " + workset);

      for (Set<String> partition : ps) {

        // Nothing to refine if already a concrete node
        if (partition.size() <= 1) {
          continue;
        }

        Map<String, Set<EquivalenceEdge>> groupMap = new HashMap<>();

        for (String router : partition) {

          Set<EquivalenceEdge> groups = new HashSet<>();
          groupMap.put(router, groups);

          // TODO: don't look at edges within the same group?

          List<GraphEdge> edges = _graph.getEdgeMap().get(router);
          for (GraphEdge edge : edges) {
            if (!edge.isAbstract()) {
              String peer = edge.getPeer();
              InterfacePolicy ipol = importPol.get(edge);
              GraphEdge otherEnd = _graph.getOtherEnd().get(edge);
              InterfacePolicy epol = null;
              if (otherEnd != null) {
                epol = exportPol.get(otherEnd);
              }

              // For external neighbors, we don't split a partition
              Integer peerGroup;
              if (peer != null) {
                peerGroup = workset.getHandle(peer);
              } else {
                peerGroup = -1;
              }

              EquivalenceEdge pair = new EquivalenceEdge(peerGroup, ipol, epol);
              groups.add(pair);
              // System.out.println("    Group: " + pair.getKey() + "," + pair.getValue());
            }
          }
        }

        Map<Set<EquivalenceEdge>, Set<String>> inversePolicyMap = new HashMap<>();
        groupMap.forEach(
            (router, groupPairs) -> {
              Set<String> routers =
                  inversePolicyMap.computeIfAbsent(groupPairs, gs -> new HashSet<>());
              routers.add(router);
            });

        // Only add changed to the list
        for (Set<String> collection : inversePolicyMap.values()) {
          if (!ps.contains(collection)) {
            todo.add(collection);
          }
        }

        // System.out.println("Todo now: " + todo);
      }

      // Now divide the abstraction further
      for (Set<String> partition : todo) {
        workset.split(partition);
      }

    } while (!todo.isEmpty());

    Graph abstractGraph = createAbstractNetwork(workset, devices);
    return new EquivalenceClass(prefixes, abstractGraph);

    // System.out.println("EC: " + prefixes);
    // System.out.println("Final abstraction: " + workset.partitions());
    // System.out.println("Original Size: " + allDevices.size());
    // System.out.println("Compressed: " + workset.partitions().size());
  }


  /*
   * Given a collection of abstract roles, computes a set of canonical
   * representatives from each role that serve as the abstraction.
   */
  private Set<String> chooseCanonicalRouters(UnionSplit<String> us, Set<String> dests) {
    Map<Integer, String> choosen = new HashMap<>();
    Stack<Tuple<Integer,String>> todo = new Stack<>();

    // Start with the concrete nodes
    for (String d : dests) {
      Integer i = us.getHandle(d);
      Tuple<Integer, String> tup = new Tuple<>(i,d);
      choosen.put(i,d);
      todo.push(tup);
    }

    // Need to choose representatives that are connected
    while (!todo.isEmpty()) {
      Tuple<Integer,String> tup = todo.pop();
      Integer i = tup.getFirst();
      String router = tup.getSecond();
      if (choosen.containsKey(i)) {
        continue;
      }
      choosen.put(i, router);
      for (GraphEdge ge : _graph.getEdgeMap().get(router)) {
        String peer = ge.getPeer();
        if (peer != null) {
          Integer j = us.getHandle(peer);
          Tuple<Integer, String> peerTup = new Tuple<>(j, peer);
          todo.push(peerTup);
        }
      }
    }
    return new HashSet<>(choosen.values());
  }

  /*
   * Creates a new Configuration from an old one for an abstract router
   * by copying the old configuration, but removing any concrete interfaces,
   * neighbors etc that do not correpond to any abstract neighbors.
   */
  private Configuration createAbstractConfig(Set<String> abstractRouters, Configuration conf) {
    Configuration abstractConf = new Configuration(conf.getHostname());
    abstractConf.setDomainName(conf.getDomainName());
    abstractConf.setConfigurationFormat(conf.getConfigurationFormat());
    abstractConf.setDnsServers(conf.getDnsServers());
    abstractConf.setDnsSourceInterface(conf.getDnsSourceInterface());
    abstractConf.setAuthenticationKeyChains(conf.getAuthenticationKeyChains());
    abstractConf.setIkeGateways(conf.getIkeGateways());
    abstractConf.setDefaultCrossZoneAction(conf.getDefaultCrossZoneAction());
    abstractConf.setIkePolicies(conf.getIkePolicies());
    abstractConf.setIkeProposals(conf.getIkeProposals());
    abstractConf.setDefaultInboundAction(conf.getDefaultInboundAction());
    abstractConf.setIp6AccessLists(conf.getIp6AccessLists());
    abstractConf.setRoute6FilterLists(conf.getRoute6FilterLists());
    abstractConf.setIpsecPolicies(conf.getIpsecPolicies());
    abstractConf.setIpsecProposals(conf.getIpsecProposals());
    abstractConf.setIpsecVpns(conf.getIpsecVpns());
    abstractConf.setLoggingServers(conf.getLoggingServers());
    abstractConf.setLoggingSourceInterface(conf.getLoggingSourceInterface());
    abstractConf.setNormalVlanRange(conf.getNormalVlanRange());
    abstractConf.setNtpServers(conf.getNtpServers());
    abstractConf.setNtpSourceInterface(conf.getNtpSourceInterface());
    abstractConf.setRoles(conf.getRoles());
    abstractConf.setSnmpSourceInterface(conf.getSnmpSourceInterface());
    abstractConf.setSnmpTrapServers(conf.getSnmpTrapServers());
    abstractConf.setTacacsServers(conf.getTacacsServers());
    abstractConf.setTacacsSourceInterface(conf.getTacacsSourceInterface());
    abstractConf.setVendorFamily(conf.getVendorFamily());
    abstractConf.setZones(conf.getZones());
    abstractConf.setCommunityLists(conf.getCommunityLists());

    SortedSet<Interface> toRetain = new TreeSet<>();
    SortedSet<Pair<Ip,Ip>> ipNeighbors = new TreeSet<>();
    SortedSet<BgpNeighbor> bgpNeighbors = new TreeSet<>();

    List<GraphEdge> edges = _graph.getEdgeMap().get(conf.getName());
    for (GraphEdge ge : edges) {
      if (abstractRouters.contains(ge.getRouter())
          && ge.getPeer() != null
          && abstractRouters.contains(ge.getPeer())) {
        toRetain.add(ge.getStart());
        Ip start = ge.getStart().getPrefix().getAddress();
        Ip end = ge.getEnd().getPrefix().getAddress();
        ipNeighbors.add(new Pair<>(start, end));
        BgpNeighbor n = _graph.getEbgpNeighbors().get(ge);
        if (n != null) {
          bgpNeighbors.add(n);
        }
      }
    }

    NavigableMap<String, Interface> abstractInterfaces = new TreeMap<>();

    conf.getInterfaces().forEach((name, iface) -> {
      if (toRetain.contains(iface)) {
        abstractInterfaces.put(name, iface);
      }
    });
    abstractConf.setInterfaces(abstractInterfaces);

    abstractConf.setVrfs(conf.getVrfs());

    Map<String, Vrf> abstractVrfs = new HashMap<>();
    conf.getVrfs().forEach((name, vrf) -> {
      Vrf abstractVrf = new Vrf(name);
      abstractVrf.setStaticRoutes(vrf.getStaticRoutes());
      abstractVrf.setIsisProcess(vrf.getIsisProcess());
      abstractVrf.setRipProcess(vrf.getRipProcess());
      abstractVrf.setSnmpServer(vrf.getSnmpServer());

      NavigableMap<String, Interface> abstractVrfInterfaces = new TreeMap<>();
      vrf.getInterfaces().forEach((iname, iface) -> {
        if (toRetain.contains(iface)) {
          abstractVrfInterfaces.put(iname, iface);
        }
      });
      abstractVrf.setInterfaces(abstractVrfInterfaces);
      abstractVrf.setInterfaceNames(new TreeSet<>(abstractVrfInterfaces.keySet()));

      OspfProcess ospf = vrf.getOspfProcess();
      if (ospf != null) {
        OspfProcess abstractOspf = new OspfProcess();
        abstractOspf.setAreas(ospf.getAreas());
        abstractOspf.setExportPolicy(ospf.getExportPolicy());
        abstractOspf.setReferenceBandwidth(ospf.getReferenceBandwidth());
        abstractOspf.setRouterId(ospf.getRouterId());

        Map<Pair<Ip,Ip>, OspfNeighbor> abstractNeighbors = new HashMap<>();
        ospf.getOspfNeighbors().forEach((pair, neighbor) -> {
          if (ipNeighbors.contains(pair)) {
            abstractNeighbors.put(pair, neighbor);
          }
        });
        abstractOspf.setOspfNeighbors(abstractNeighbors);
        abstractVrf.setOspfProcess(abstractOspf);
      }

      BgpProcess bgp = vrf.getBgpProcess();
      if (bgp != null) {
        BgpProcess abstractBgp = new BgpProcess();
        abstractBgp.setMultipathEbgp(bgp.getMultipathEbgp());
        abstractBgp.setMultipathIbgp(bgp.getMultipathIbgp());
        abstractBgp.setRouterId(bgp.getRouterId());
        abstractBgp.setOriginationSpace(bgp.getOriginationSpace());

        // TODO: set bgp neighbors accordingly
        SortedMap<Prefix, BgpNeighbor> abstractBgpNeighbors = new TreeMap<>();
        bgp.getNeighbors().forEach((prefix, neighbor) -> {
          if (bgpNeighbors.contains(neighbor)) {
            abstractBgpNeighbors.put(prefix, neighbor);
          }
        });
        abstractBgp.setNeighbors(abstractBgpNeighbors);
      }

      abstractVrfs.put(name, abstractVrf);
    });

    return abstractConf;
  }

  /*
   * Create a collection of abstract configurations given the roles computed
   * and the collection of concrete devices. Chooses a collection of canonical
   * representatives from each role, and then removes all their interfaces etc
   * that connect to non-canonical routers.
   */
  private Graph createAbstractNetwork(UnionSplit<String> us, Set<String> dests) {
    Set<String> abstractRouters = chooseCanonicalRouters(us, dests);
    Map<String, Configuration> newConfigs = new HashMap<>();
    _graph.getConfigurations().forEach((router,conf) -> {
      if (abstractRouters.contains(router)) {
        Configuration abstractConf = createAbstractConfig(abstractRouters, conf);
        newConfigs.put(router, abstractConf);
      }
    });
    return new Graph(_batfish, newConfigs);
  }



  public AnswerElement asAnswer() {
    long start = System.currentTimeMillis();
    int i = 0;
    for (EquivalenceClass ec : this) {
      i++;
      System.out.println("EC: " + i);
    }
    AbstractionAnswerElement answer = new AbstractionAnswerElement();
    long end = System.currentTimeMillis();
    System.out.println("Total time (sec): " + ((double) end - start) / 1000);
    return answer;
  }

  private class AbstractionIterator implements Iterator<EquivalenceClass> {

    private Iterator<Entry<Set<String>, List<Prefix>>> _iter;

    AbstractionIterator() {
      _iter = _destinationMap.entrySet().iterator();
    }

    @Override public boolean hasNext() {
      return _iter.hasNext();
    }

    @Override public EquivalenceClass next() {
      Entry<Set<String>, List<Prefix>> x = _iter.next();
      Set<String> devices = x.getKey();
      List<Prefix> prefixes = x.getValue();
      return computeAbstraction(devices, prefixes);
    }
  }

}
