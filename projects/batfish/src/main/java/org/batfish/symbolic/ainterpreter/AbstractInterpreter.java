package org.batfish.symbolic.ainterpreter;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDPairing;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.Vrf;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.NodesSpecifier;
import org.batfish.datamodel.questions.smt.HeaderLocationQuestion;
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.answers.AIRoutesAnswerElement;
import org.batfish.symbolic.bdd.BDDAcl;
import org.batfish.symbolic.bdd.BDDInteger;
import org.batfish.symbolic.bdd.BDDNetwork;
import org.batfish.symbolic.bdd.BDDRouteConfig;
import org.batfish.symbolic.bdd.BDDRouteFactory;
import org.batfish.symbolic.bdd.BDDRouteFactory.BDDRoute;
import org.batfish.symbolic.bdd.BDDTransferFunction;
import org.batfish.symbolic.smt.EdgeType;

// TODO: Take ACLs into account. Will likely require using a single Factory object
// TODO: Compute end-to-end reachability (i.e., transitive closure)

/*
 * Computes an overapproximation of some concrete set of states in the
 * network using abstract interpretation
 */
public class AbstractInterpreter {

  // The network topology graph
  private Graph _graph;

  // BDDs representing every transfer function in the network
  private BDDNetwork _network;

  // BDD Route factory
  private BDDRouteFactory _routeFactory;

  // Convert acls to a unique identifier
  private FiniteIndexMap<BDDAcl> _aclIndexes;

  // The question asked
  private HeaderLocationQuestion _question;

  // A collection of single node BDDs representing the individual variables
  private BDDRoute _variables;

  // The set of community and protocol BDD variables that we will quantify away
  private BDD _communityAndProtocolBits;

  // A cache of BDDs representing a prefix length exact match
  private Map<Integer, BDD> _lengthCache;

  // A cache of sets of BDDs for a given prefix length to quantify away
  private Map<Integer, BDD> _dstBitsCache;

  // The prefix length bits that we will quantify away
  private BDD _lenBits;

  // The source router bits that we will quantify away
  private BDD _tempRouterBits;

  // Substitution of dst router bits for src router bits used to compute transitive closure
  private BDDPairing _dstToTempRouterSubstitution;

  // Substitution of dst router bits for src router bits used to compute transitive closure
  private BDDPairing _srcToTempRouterSubstitution;

  /*
   * Construct an abstract ainterpreter the answer a particular question.
   * This could be done more in a fashion like Batfish, where we run
   * the computation once and then answer many questions.
   */
  public AbstractInterpreter(IBatfish batfish, HeaderLocationQuestion q) {
    _graph = new Graph(batfish);
    _question = q;
    _lengthCache = new HashMap<>();
    _dstBitsCache = new HashMap<>();
    _lenBits = BDDRouteFactory.factory.one();
    NodesSpecifier ns = new NodesSpecifier(_question.getIngressNodeRegex());
    BDDRouteConfig config = new BDDRouteConfig(true);
    _routeFactory = new BDDRouteFactory(_graph, config);
    _variables = _routeFactory.variables();
    _network = BDDNetwork.create(_graph, ns, config, false);
    Set<BDDAcl> acls = new HashSet<>();
    acls.addAll(_network.getInAcls().values());
    acls.addAll(_network.getOutAcls().values());
    _aclIndexes = new FiniteIndexMap<>(acls);

    BDD[] srcRouterBits = _variables.getSrcRouter().getInteger().getBitvec();
    BDD[] tempRouterBits = _variables.getRouterTemp().getInteger().getBitvec();
    BDD[] dstRouterBits = _variables.getDstRouter().getInteger().getBitvec();

    _dstToTempRouterSubstitution = BDDRouteFactory.factory.makePair();
    _srcToTempRouterSubstitution = BDDRouteFactory.factory.makePair();

    _tempRouterBits = BDDRouteFactory.factory.one();
    for (int i = 0; i < srcRouterBits.length; i++) {
      _tempRouterBits = _tempRouterBits.and(tempRouterBits[i]);
      _dstToTempRouterSubstitution.set(dstRouterBits[i].var(), tempRouterBits[i].var());
      _srcToTempRouterSubstitution.set(srcRouterBits[i].var(), tempRouterBits[i].var());
    }
  }

  /*
   * Initialize what prefixes are 'originated' at each router
   * and for each routing protocol. These are used as the
   * starting values for the fixed point computation.
   */
  private void initializeOriginatedPrefixes(
      Map<String, Set<Prefix>> originatedOSPF,
      Map<String, Set<Prefix>> originatedConnected,
      Map<String, Map<String, Set<Prefix>>> originatedStatic) {

    for (String router : _graph.getRouters()) {
      Configuration conf = _graph.getConfigurations().get(router);
      Vrf vrf = conf.getDefaultVrf();
      if (vrf.getOspfProcess() != null) {
        originatedOSPF.put(router, Graph.getOriginatedNetworks(conf, Protocol.OSPF));
      }
      if (vrf.getStaticRoutes() != null) {

        for (StaticRoute sr : conf.getDefaultVrf().getStaticRoutes()) {
          if (sr.getNetwork() != null) {
            Map<String, Set<Prefix>> map =
                originatedStatic.computeIfAbsent(router, k -> new HashMap<>());
            Set<Prefix> pfxs = map.computeIfAbsent(sr.getNextHop(), k -> new HashSet<>());
            pfxs.add(sr.getNetwork());
          }
        }
      }
      originatedConnected.put(router, Graph.getOriginatedNetworks(conf, Protocol.CONNECTED));
    }
  }

  /*
   * Make a BDD representing an exact prefix length match.
   * For efficiency, these results are cached for each prefix length.
   */
  private BDD makeLength(int i) {
    BDD len = _lengthCache.get(i);
    if (len == null) {
      BDDInteger pfxLen = _variables.getPrefixLength();
      BDDInteger newVal = new BDDInteger(pfxLen);
      newVal.setValue(i);
      len = BDDRouteFactory.factory.one();
      for (int j = 0; j < pfxLen.getBitvec().length; j++) {
        BDD var = pfxLen.getBitvec()[j];
        BDD val = newVal.getBitvec()[j];
        if (val.isOne()) {
          len = len.and(var);
        } else {
          len.andWith(var.not());
        }
      }
      _lengthCache.put(i, len);
    }
    return len;
  }

  /*
   * Compute the set of BDD variables to quantify away for a given prefix length.
   * The boolean indicates whether the router bits should be removed
   * For efficiency, these values are cached.
   */
  private BDD removeBits(int len) {
    BDD removeBits = _dstBitsCache.get(len);
    if (removeBits == null) {
      removeBits = BDDRouteFactory.factory.one();
      for (int i = len; i < 32; i++) {
        BDD x = _variables.getPrefix().getBitvec()[i];
        removeBits = removeBits.and(x);
      }
      _dstBitsCache.put(len, removeBits);
    }
    return removeBits;
  }

  /*
   * Iteratively computes a fixed point over an abstract domain.
   * Starts with some initial advertisements that are 'originated'
   * by different protocols and maintains an underapproximation of
   * reachable sets at each router for every iteration.
   */
  private <T> Map<String, AbstractFib<BDD>> computeFixedPoint(IAbstractDomain<T> domain) {

    Map<String, Set<Prefix>> originatedOSPF = new HashMap<>();
    Map<String, Set<Prefix>> originatedConnected = new HashMap<>();
    Map<String, Map<String, Set<Prefix>>> originatedStatic = new HashMap<>();
    initializeOriginatedPrefixes(originatedOSPF, originatedConnected, originatedStatic);

    Map<String, AbstractRib<T>> reachable = new HashMap<>();
    Set<String> initialRouters = new HashSet<>();

    long t = System.currentTimeMillis();
    for (String router : _graph.getRouters()) {
      Set<Prefix> ospfPrefixes = originatedOSPF.get(router);
      Set<Prefix> connPrefixes = originatedConnected.get(router);
      Map<String, Set<Prefix>> staticPrefixes = originatedStatic.get(router);

      if (staticPrefixes != null && !staticPrefixes.isEmpty()) {
        initialRouters.add(router);
      }
      if (ospfPrefixes != null && !ospfPrefixes.isEmpty()) {
        initialRouters.add(router);
      }

      T bgp = domain.bot();
      T ospf = domain.value(router, Protocol.OSPF, ospfPrefixes);
      T conn = domain.value(router, Protocol.CONNECTED, connPrefixes);
      T stat = domain.bot();
      if (staticPrefixes != null) {
        for (Entry<String, Set<Prefix>> e : staticPrefixes.entrySet()) {
          String neighbor = e.getKey();
          Set<Prefix> prefixes = e.getValue();
          neighbor = (neighbor == null ? router : neighbor);
          T statForNeighbor = domain.value(neighbor, Protocol.STATIC, prefixes);
          stat = domain.merge(stat, statForNeighbor);
        }
      }

      T rib = domain.merge(domain.merge(domain.merge(bgp, ospf), stat), conn);
      AbstractRib<T> abstractRib = new AbstractRib<>(bgp, ospf, stat, conn, rib, new BitSet());
      reachable.put(router, abstractRib);
    }

    System.out.println("Time for network to BDD conversion: " + (System.currentTimeMillis() - t));

    // Initialize the workset
    Set<String> updateSet = new HashSet<>();
    Queue<String> update = new ArrayDeque<>();
    for (String router : initialRouters) {
      updateSet.add(router);
      update.add(router);
    }

    t = System.currentTimeMillis();
    while (!update.isEmpty()) {
      String router = update.poll();
      updateSet.remove(router);
      Configuration conf = _graph.getConfigurations().get(router);

      AbstractRib<T> r = reachable.get(router);
      T routerOspf = r.getOspfRib();
      T routerRib = r.getMainRib();
      BitSet routerAclsSoFar = r.getAclIds();

      // System.out.println("Looking at router: " + router);
      // System.out.println("RIB is " + "\n" + _variables.dot(domain.toBdd(routerRib)));

      for (GraphEdge ge : _graph.getEdgeMap().get(router)) {
        GraphEdge rev = _graph.getOtherEnd().get(ge);
        if (ge.getPeer() != null && rev != null) {

          String neighbor = ge.getPeer();
          // System.out.println("  Got neighbor: " + neighbor);

          AbstractRib<T> nr = reachable.get(neighbor);
          T neighborConn = nr.getConnectedRib();
          T neighborStat = nr.getStaticRib();
          T neighborBgp = nr.getBgpRib();
          T neighborOspf = nr.getOspfRib();
          T neighborRib = nr.getMainRib();
          BitSet neighborAclsSoFar = nr.getAclIds();

          T newNeighborOspf = neighborOspf;
          T newNeighborBgp = neighborBgp;

          // Update static
          List<StaticRoute> srs = _graph.getStaticRoutes().get(neighbor, rev.getStart().getName());
          if (srs != null) {
            Set<Prefix> pfxs = new HashSet<>();
            for (StaticRoute sr : srs) {
              pfxs.add(sr.getNetwork());
            }
            T stat = domain.value(neighbor, Protocol.STATIC, pfxs);
          }

          // Update OSPF
          if (_graph.isEdgeUsed(conf, Protocol.OSPF, ge)) {
            newNeighborOspf = domain.merge(neighborOspf, routerOspf);
          }

          // Update BGP
          if (_graph.isEdgeUsed(conf, Protocol.BGP, ge)) {
            BDDTransferFunction exportFilter = _network.getExportBgpPolicies().get(ge);
            BDDTransferFunction importFilter = _network.getImportBgpPolicies().get(rev);

            T tmpBgp = routerRib;
            if (exportFilter != null) {
              /* System.out.println(
              "  Export filter for "
                  + "\n"
                  + factory.variables().dot(exportFilter.getFilter())); */
              EdgeTransformer exp = new EdgeTransformer(ge, EdgeType.EXPORT, exportFilter);
              tmpBgp = domain.transform(tmpBgp, exp);
            }

            if (importFilter != null) {
              EdgeTransformer imp = new EdgeTransformer(ge, EdgeType.IMPORT, importFilter);
              tmpBgp = domain.transform(tmpBgp, imp);
            }

            // System.out.println(
            //    "  After processing: " + "\n" + _variables.dot(domain.toBdd(tmpBgp)));

            newNeighborBgp = domain.merge(neighborBgp, tmpBgp);
          }

          // Update set of relevant ACLs so far
          BitSet newNeighborAclsSoFar = (BitSet) neighborAclsSoFar.clone();
          newNeighborAclsSoFar.or(routerAclsSoFar);
          BDDAcl out = _network.getOutAcls().get(rev);
          if (out != null) {
            int idx = _aclIndexes.index(out);
            newNeighborAclsSoFar.set(idx);
          }
          BDDAcl in = _network.getInAcls().get(ge);
          if (in != null) {
            int idx = _aclIndexes.index(in);
            newNeighborAclsSoFar.set(idx);
          }

          // System.out.println("  New Headerspace: \n" + _variables.dot(newHeaderspace));

          // Update RIB
          T newNeighborRib =
              domain.merge(
                  domain.merge(domain.merge(newNeighborBgp, newNeighborOspf), neighborStat),
                  neighborConn);

          // If changed, then add it to the workset
          if (!newNeighborRib.equals(neighborRib) || !newNeighborOspf.equals(neighborOspf)) {
            AbstractRib<T> newAbstractRib =
                new AbstractRib<>(
                    newNeighborBgp,
                    newNeighborOspf,
                    neighborStat,
                    neighborConn,
                    newNeighborRib,
                    newNeighborAclsSoFar);
            reachable.put(neighbor, newAbstractRib);
            if (!updateSet.contains(neighbor)) {
              updateSet.add(neighbor);
              update.add(neighbor);
            }
          }
        }
      }
    }

    Map<String, AbstractFib<BDD>> reach = new HashMap<>();
    for (Entry<String, AbstractRib<T>> e : reachable.entrySet()) {
      AbstractRib<T> val = e.getValue();
      BDD bgp = domain.toBdd(val.getMainRib());
      BDD ospf = domain.toBdd(val.getOspfRib());
      BDD conn = domain.toBdd(val.getConnectedRib());
      BDD stat = domain.toBdd(val.getStaticRib());
      BDD rib = domain.toBdd(val.getMainRib());
      AbstractRib<BDD> bddRib = new AbstractRib<>(bgp, ospf, stat, conn, rib, val.getAclIds());
      AbstractFib<BDD> bddFib = new AbstractFib<>(bddRib, toHeaderspace(rib));
      reach.put(e.getKey(), bddFib);
    }

    System.out.println("Time to compute fixedpoint: " + (System.currentTimeMillis() - t));

    return reach;
  }

  /*
   * Convert a RIB represented as a BDD to the actual headerspace that
   * it matches. Normally, the final destination router is preserved
   * in this operation. The removeRouters flag allows the routers to
   * be projected away.
   */
  private BDD toHeaderspace(BDD rib) {
    BDD pfxOnly = rib.exist(_communityAndProtocolBits);
    if (pfxOnly.isZero()) {
      return pfxOnly;
    }
    BDD acc = BDDRouteFactory.factory.zero();
    for (int i = 32; i >= 0; i--) {
      // pick out the routes with prefix length i
      BDD len = makeLength(i);
      BDD withLen = pfxOnly.and(len);
      if (withLen.isZero()) {
        continue;
      }
      // quantify out bits i+1 to 32
      BDD removeBits = removeBits(i);
      if (!removeBits.isOne()) {
        withLen = withLen.exist(removeBits);
      }
      // accumulate resulting headers
      acc.orWith(withLen);
    }

    if (_lenBits.isOne()) {
      BDD[] pfxLen = _variables.getPrefixLength().getBitvec();
      for (BDD x : pfxLen) {
        _lenBits = _lenBits.and(x);
      }
    }
    return acc.exist(_lenBits);
  }

  /*
   * Evaluate a particular packet to check reachability to a router
   */
  /* private <T> boolean reachable(
      IAbstractDomain<T> domain, Map<String, AbstractRib<T>> ribs, String router, Ip dstIp) {
    BDD ip =
        BDDUtils.firstBitsEqual(
            BDDRouteFactory.factory, _variables.getPrefix().getBitvec(), dstIp, 32);

    AbstractRib<T> rib = ribs.get(router);
    BDD headerspace = toHeaderspace(domain.toBdd(rib.getMainRib()));

    headerspace = headerspace.and(ip);
  } */

  /*
   * Computes the transitive closure of a fib
   */
  private BDD transitiveClosure(BDD fibs) {
    BDD fibsTemp = fibs.replace(_srcToTempRouterSubstitution);
    Set<BDD> seenFibs = new HashSet<>();
    BDD newFib = fibs;
    do {
      seenFibs.add(newFib);

      // System.out.println("old: ");
      // System.out.println(_variables.dot(oldFib));

      newFib = newFib.replace(_dstToTempRouterSubstitution);

      // System.out.println("replace dst with src: ");
      // System.out.println(_variables.dot(newFib));

      newFib = newFib.and(fibsTemp);

      // System.out.println("step with (and): ");
      // System.out.println(_variables.dot(newFib));

      newFib = newFib.exist(_tempRouterBits);

      // System.out.println("remove src bits: ");
      // System.out.println(_variables.dot(newFib));

    } while (!seenFibs.contains(newFib));
    return newFib;
  }

  private <T> BDD transitiveClosure(Map<String, AbstractFib<T>> fibs) {
    BDD allFibs = BDDRouteFactory.factory.zero();
    for (Entry<String, AbstractFib<T>> e : fibs.entrySet()) {
      String router = e.getKey();
      AbstractFib<T> fib = e.getValue();
      BDD routerBdd = _variables.getSrcRouter().value(router);
      BDD annotatedFib = fib.getHeaderspace().and(routerBdd);
      allFibs = allFibs.orWith(annotatedFib);
    }
    return transitiveClosure(allFibs);
  }

  /*
   * Print a collection of routes in a BDD as representative examples
   * that can be understood by a human.
   */
  private SortedSet<RibEntry> toRoutes(String hostname, BDD rib) {
    SortedSet<RibEntry> routes = new TreeSet<>();
    List assignments = rib.allsat();
    for (Object o : assignments) {
      long pfx = 0;
      int proto = 0;
      int len = 0;
      int router = 0;
      byte[] variables = (byte[]) o;
      for (int i = 0; i < variables.length; i++) {
        byte var = variables[i];
        String name = _variables.name(i);
        // avoid temporary variables
        if (name != null) {
          boolean isTrue = (var == 1);
          if (isTrue) {
            if (name.startsWith("proto") && !name.contains("'")) {
              int num = Integer.parseInt(name.substring(5));
              proto = proto + (1 << (2 - num));
            } else if (name.startsWith("pfxLen") && !name.contains("'")) {
              int num = Integer.parseInt(name.substring(6));
              len = len + (1 << (6 - num));
            } else if (name.startsWith("pfx") && !name.contains("'")) {
              int num = Integer.parseInt(name.substring(3));
              pfx = pfx + (1L << (32 - num));
            } else if (name.startsWith("dstRouter") && !name.contains("'")) {
              int num = Integer.parseInt(name.substring(9));
              router =
                  router + (1 << (_variables.getDstRouter().getInteger().getBitvec().length - num));
            }
          }
        }
      }

      String r = _routeFactory.getRouter(router);
      Protocol prot = BDDRouteFactory.allProtos.get(proto);
      Ip ip = new Ip(pfx);
      Prefix p = new Prefix(ip, len);
      RibEntry entry = new RibEntry(hostname, p, Protocol.toRoutingProtocol(prot), r);
      routes.add(entry);
    }

    return routes;
  }

  /*
   * Variables that will be existentially quantified away
   */
  private void initializeQuantificationVariables() {
    _communityAndProtocolBits = BDDRouteFactory.factory.one();
    BDD[] protoHistory = _variables.getProtocolHistory().getInteger().getBitvec();
    for (BDD x : protoHistory) {
      _communityAndProtocolBits = _communityAndProtocolBits.and(x);
    }
    for (Entry<CommunityVar, BDD> e : _variables.getCommunities().entrySet()) {
      BDD c = e.getValue();
      _communityAndProtocolBits = _communityAndProtocolBits.and(c);
    }
  }

  /*
   * Compute an underapproximation of all-pairs reachability
   */
  public AnswerElement routes() {
    initializeQuantificationVariables();
    ReachabilityDomain domain = new ReachabilityDomain(_variables, _communityAndProtocolBits);
    Map<String, AbstractFib<BDD>> reachable = computeFixedPoint(domain);

    // long t = System.currentTimeMillis();
    // BDD fibs = transitiveClosure(reachable);
    // System.out.println("Transitive closure: " + (System.currentTimeMillis() - t));

    SortedSet<RibEntry> routes = new TreeSet<>();
    SortedMap<String, SortedSet<RibEntry>> routesByHostname = new TreeMap<>();

    for (Entry<String, AbstractFib<BDD>> e : reachable.entrySet()) {
      String router = e.getKey();
      AbstractFib<BDD> fib = e.getValue();
      BDD rib = fib.getRib().getMainRib();
      SortedSet<RibEntry> entries = toRoutes(router, rib);
      routesByHostname.put(router, entries);
      routes.addAll(entries);
    }

    AIRoutesAnswerElement answer = new AIRoutesAnswerElement();
    answer.setRoutes(routes);
    answer.setRoutesByHostname(routesByHostname);
    return answer;
  }
}