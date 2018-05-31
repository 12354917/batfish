package org.batfish.symbolic.ainterpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDPairing;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.OspfType;
import org.batfish.symbolic.bdd.BDDFiniteDomain;
import org.batfish.symbolic.bdd.BDDInteger;
import org.batfish.symbolic.bdd.BDDNetFactory;
import org.batfish.symbolic.bdd.BDDRoute;
import org.batfish.symbolic.bdd.BDDTransferFunction;
import org.batfish.symbolic.bdd.BDDUtils;
import org.batfish.symbolic.bdd.SatAssignment;

public class DomainHelper {

  // Reference to the factory
  private BDDNetFactory _netFactory;

  // Access to the route variables
  private BDDRoute _variables;

  // A substitution pairing that we will reuse
  private BDDPairing _pairing;

  // A cache of BDDs representing a prefix length exact match
  private Map<Integer, BDD> _lengthCache;

  // A cache of sets of BDDs for a given prefix length to quantify away
  private Map<Integer, BDD> _dstBitsCache;

  // The prefix length bits that we will quantify away
  private BDD _lenBits;

  // The destination router bits that we will quantify away
  private BDD _dstRouterBits;

  // The set of all modifed variables that we will quantify away
  private BDD _allQuantifyBits;

  // The set of all modifed variables that we will quantify away
  private BDD _modifiedBits;

  // The source router bits that we will quantify away
  private BDD _tempRouterBits;

  // Bits for attributes that are transient (not transitive)
  private BDD _transientBits;

  // Substitution of dst router bits for src router bits used to compute transitive closure
  private BDDPairing _dstToTempRouterSubstitution;

  // Substitution of dst router bits for src router bits used to compute transitive closure
  private BDDPairing _srcToTempRouterSubstitution;

  public DomainHelper(BDDNetFactory netFactory) {
    _netFactory = netFactory;
    _variables = netFactory.routeVariables();
    _pairing = _netFactory.makePair();
    _lengthCache = new HashMap<>();
    _dstBitsCache = new HashMap<>();
    _lenBits = _netFactory.one();
    _transientBits = _netFactory.one();

    if (_netFactory.getConfig().getKeepRouters()) {
      BDD[] srcRouterBits = _variables.getSrcRouter().getInteger().getBitvec();
      BDD[] tempRouterBits = _variables.getRouterTemp().getInteger().getBitvec();
      BDD[] dstRouterBits = _variables.getDstRouter().getInteger().getBitvec();

      _dstToTempRouterSubstitution = _netFactory.makePair();
      _srcToTempRouterSubstitution = _netFactory.makePair();

      _tempRouterBits = _netFactory.one();
      for (int i = 0; i < srcRouterBits.length; i++) {
        _tempRouterBits = _tempRouterBits.and(tempRouterBits[i]);
        _dstToTempRouterSubstitution.set(dstRouterBits[i].var(), tempRouterBits[i].var());
        _srcToTempRouterSubstitution.set(srcRouterBits[i].var(), tempRouterBits[i].var());
      }

      _dstRouterBits = _netFactory.one();
      for (BDD x : dstRouterBits) {
        _dstRouterBits = _dstRouterBits.and(x);
      }
    }

    _lenBits = _netFactory.one();
    BDD[] pfxLen = _variables.getPrefixLength().getBitvec();
    for (BDD x : pfxLen) {
      _lenBits = _lenBits.and(x);
    }

    if (_netFactory.getConfig().getKeepNextHopIp()) {
      for (BDD x : _variables.getNextHopIp().getInteger().getBitvec()) {
        _transientBits = _transientBits.and(x);
      }
    }

    _allQuantifyBits = _netFactory.one();
    if (_netFactory.getConfig().getKeepProtocol()) {
      BDD[] bits = _variables.getProtocolHistory().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepCommunities()) {
      for (Entry<CommunityVar, BDD> e : _variables.getCommunities().entrySet()) {
        BDD c = e.getValue();
        _allQuantifyBits = _allQuantifyBits.and(c);
      }
    }
    if (_netFactory.getConfig().getKeepRRClient()) {
      _allQuantifyBits = _allQuantifyBits.and(_variables.getFromRRClient());
    }
    if (_netFactory.getConfig().getKeepMed()) {
      BDD[] bits = _variables.getMed().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepMetric()) {
      BDD[] bits = _variables.getMetric().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepOspfMetric()) {
      BDD[] bits = _variables.getOspfMetric().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepLp()) {
      BDD[] bits = _variables.getLocalPref().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepAd()) {
      BDD[] bits = _variables.getAdminDist().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
    if (_netFactory.getConfig().getKeepNextHopIp()) {
      BDD[] bits = _variables.getNextHopIp().getInteger().getBitvec();
      for (BDD x : bits) {
        _allQuantifyBits = _allQuantifyBits.and(x);
      }
    }
  }

  public BDD applyTransformerMods(BDD input, EdgeTransformer t) {
    BDDTransferFunction f = t.getBddTransfer();
    BDDRoute mods = f.getRoute();
    _pairing.reset();
    _modifiedBits = _netFactory.one();
    // reverse order from BDD order is the best
    input = modifyFromRRClient(input, mods.getFromRRClient());
    input = modifyCommunities(input, mods);
    input = modifyMed(input, mods);
    input = modifyMetric(input, mods);
    input = modifyOspfMetric(input, mods);
    input = modifyLocalPref(input, mods);
    input = modifyAdminDist(input, mods);
    input = modifyNextHop(input, mods);
    input = modifyProtocol(input, t.getProtocol());
    input = input.exist(_modifiedBits);
    input = input.replaceWith(_pairing);
    return input;
  }

  private BDD modifyInteger(
      BDD input, BDDInteger vars, BDDInteger tempVars, BDDInteger mods, int modIndex) {
    BDD[] vec = vars.getBitvec();
    for (int i = vec.length - 1; i >= 0; i--) {
      BDD modBit = mods.getBitvec()[i];
      if (notIdentity(modBit, modIndex)) {
        BDD oldBit = vec[i];
        BDD newBit = tempVars.getBitvec()[i];
        input.andWith(newBit.biimp(modBit));
        _pairing.set(newBit.var(), oldBit.var());
        _modifiedBits = _modifiedBits.and(oldBit);
      }
    }
    return input;
  }

  private <T> BDD modifyFiniteDomain(
      BDD input,
      BDDFiniteDomain<T> vars,
      BDDFiniteDomain<T> tempVars,
      BDDFiniteDomain<T> mods,
      int modIndex) {
    // System.out.println("-----------------------");
    Map<BDD, Set<Integer>> map = new HashMap<>();
    BDD[] vec = vars.getInteger().getBitvec();
    for (int i = vec.length - 1; i >= 0; i--) {
      BDD modBit = mods.getInteger().getBitvec()[i];
      // Set<Integer> is = map.computeIfAbsent(modBit, k -> new HashSet<>());
      // is.add(i);
      if (notIdentity(modBit, modIndex)) {
        BDD oldBit = vec[i];
        BDD newBit = tempVars.getInteger().getBitvec()[i];
        input.andWith(newBit.biimp(modBit));
        _pairing.set(newBit.var(), oldBit.var());
        _modifiedBits = _modifiedBits.and(oldBit);
      }
    }
    // System.out.println("Unique: " + map.size() + " out of " + vec.length);
    // System.out.println("-----------------------");
    return input;
  }

  private BDD modifyInteger(
      BDD input, BDDInteger vars, BDDInteger tempVars, int value, int modIndex) {
    BDDInteger copy = new BDDInteger(vars);
    copy.setValue(value);
    return modifyInteger(input, vars, tempVars, copy, modIndex);
  }

  private <T> BDD modifyFiniteDomain(
      BDD input, BDDFiniteDomain<T> vars, BDDFiniteDomain<T> tempVars, T elt, int modIndex) {
    BDDFiniteDomain<T> copy = new BDDFiniteDomain<>(vars);
    copy.setValue(elt);
    return modifyFiniteDomain(input, vars, tempVars, copy, modIndex);
  }

  private BDD modifyBit(BDD input, BDD var, BDD tempVar, BDD mod, int modIndex) {
    if (notIdentity(mod, modIndex)) {
      input.andWith(tempVar.biimp(mod));
      _pairing.set(tempVar.var(), var.var());
      _modifiedBits = _modifiedBits.and(var);
    }
    return input;
  }

  // Used as an optimization to avoid substitution for variables that are not modified
  private boolean notIdentity(BDD x, int index) {
    if (x.isOne() || x.isZero()) {
      return true;
    }
    return !x.low().isZero() || !x.high().isOne() || x.var() != index;
  }

  private BDD modifyAdminDist(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepAd()) {
      BDDFiniteDomain<Long> vars = _variables.getAdminDist();
      BDDFiniteDomain<Long> tempVars = _variables.getAdminDistTemp();
      BDDFiniteDomain<Long> mod = mods.getAdminDist();
      input = modifyFiniteDomain(input, vars, tempVars, mod, _netFactory.getIndexAdminDist());
    }
    return input;
  }

  private BDD modifyMetric(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepMetric()) {
      BDDInteger vars = _variables.getMetric();
      BDDInteger tempVars = _variables.getMetricTemp();
      BDDInteger mod = mods.getMetric();
      input = modifyInteger(input, vars, tempVars, mod, _netFactory.getIndexMetric());
    }
    return input;
  }

  private BDD modifyNextHop(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepNextHopIp()) {
      BDDFiniteDomain<Ip> vars = _variables.getNextHopIp();
      BDDFiniteDomain<Ip> tempVars = _variables.getNextHopIpTemp();
      BDDFiniteDomain<Ip> mod = mods.getNextHopIp();
      input = modifyFiniteDomain(input, vars, tempVars, mod, _netFactory.getIndexNextHopIp());
    }
    return input;
  }

  private BDD modifyMed(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepMed()) {
      BDDFiniteDomain<Long> vars = _variables.getMed();
      BDDFiniteDomain<Long> tempVars = _variables.getMedTemp();
      BDDFiniteDomain<Long> mod = mods.getMed();
      input = modifyFiniteDomain(input, vars, tempVars, mod, _netFactory.getIndexMed());
    }
    return input;
  }

  private BDD modifyLocalPref(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepLp()) {
      BDDFiniteDomain<Integer> vars = _variables.getLocalPref();
      BDDFiniteDomain<Integer> tempVars = _variables.getLocalPrefTemp();
      BDDFiniteDomain<Integer> mod = mods.getLocalPref();
      input = modifyFiniteDomain(input, vars, tempVars, mod, _netFactory.getIndexLocalPref());
    }
    return input;
  }

  private BDD modifyOspfMetric(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepOspfMetric()) {
      BDDFiniteDomain<OspfType> vars = _variables.getOspfMetric();
      BDDFiniteDomain<OspfType> tempVars = _variables.getOspfMetricTemp();
      BDDFiniteDomain<OspfType> mod = mods.getOspfMetric();
      input = modifyFiniteDomain(input, vars, tempVars, mod, _netFactory.getIndexOspfMetric());
    }
    return input;
  }

  private BDD modifyFromRRClient(BDD input, BDD mod) {
    if (_netFactory.getConfig().getKeepRRClient()) {
      BDD var = _variables.getFromRRClient();
      BDD tempVar = _variables.getFromRRClientTemp();
      input = modifyBit(input, var, tempVar, mod, _netFactory.getIndexRRClient());
    }
    return input;
  }

  private BDD modifyProtocol(BDD input, RoutingProtocol proto) {
    if (_netFactory.getConfig().getKeepProtocol()) {
      BDDFiniteDomain<RoutingProtocol> vars = _variables.getProtocolHistory();
      BDDFiniteDomain<RoutingProtocol> tempVars = _variables.getProtocolHistoryTemp();
      input =
          modifyFiniteDomain(input, vars, tempVars, proto, _netFactory.getIndexRoutingProtocol());
    }
    return input;
  }

  private BDD modifyCommunities(BDD input, BDDRoute mods) {
    if (_netFactory.getConfig().getKeepCommunities()) {
      for (Entry<CommunityVar, BDD> e : _variables.getCommunities().entrySet()) {
        CommunityVar cvar = e.getKey();
        BDD mod = mods.getCommunities().get(cvar);
        BDD var = e.getValue();
        BDD tempVar = _variables.getCommunitiesTemp().get(cvar);
        int index =
            _netFactory.getIndexCommunities() + _variables.getCommunityIndexOffset().get(cvar);
        input = modifyBit(input, var, tempVar, mod, index);
      }
    }
    return input;
  }

  public BDD headerspace(BDD rib) {
    BDD pfxOnly = rib.exist(_allQuantifyBits);
    if (pfxOnly.isZero()) {
      return pfxOnly;
    }
    BDD reachablePackets = _netFactory.zero();
    for (int i = 32; i >= 0; i--) {
      BDD len = makeLength(i);
      // Get the must reach prefixes for this length
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
      reachablePackets = reachablePackets.orWith(withLen);
    }
    reachablePackets = reachablePackets.exist(_lenBits);
    return reachablePackets;
  }

  /*
   * Convert a pair of an underapproximation of the RIB and an overapproximation
   * of the RIB represented as a BDDs to an actual headerspace of packets that can
   * reach a destination. Normally, the final destination router is preserved
   * in this operation.
   *
   * As an example, if we have 1.2.3.0/24 learned from router X in the RIB, and
   * there can not be any more specific routes (in the overapproximation of routes)
   * that can take traffic away from this prefix, then the resulting packets matched
   * are going to be 1.2.3.*
   *
   * In general, we want to remove destinations in the overapproximated set that
   * are not in the underapproximated set. For instance, if we have prefix
   * 1.2.3.4/32 --> A in the underapproximation, and prefix
   * 1.2.3.4/32 --> {A,B} in the overapproximation, then we want to remove
   * this prefix since we can't be guaranteed that it will forward to A.
   *
   * As another example, if we have the prefix
   * 1.2.3.0/24 --> A in the underapproximated set, and another prefix
   * 1.2.3.4/32 --> B in the overapproximated set, then we only want the
   * destinations matched by the /24 but not the /32 to go to A
   *
   */
  public BDD toFib(BDD under, BDD over) {
    // Get the prefixes only by removing communities, protocol tag etc.
    BDD underPfxOnly = under.exist(getAllQuantifyBits());
    BDD overPfxOnly = over.exist(getAllQuantifyBits());

    // Early return if there is nothing in the rib
    if (underPfxOnly.isZero()) {
      return underPfxOnly;
    }

    // Prefixes that might take traffic away
    BDD allOverPrefixes = overPfxOnly.andWith(underPfxOnly.not()).exist(getDstRouterBits());

    // Accumulate the might hijack destination addresses by prefix length
    BDD mightHijack = _netFactory.zero();

    // Accumulate the reachable packets (prefix length is removed at the end)
    BDD reachablePackets = _netFactory.zero();

    // Walk starting from most specific prefix length
    for (int i = 32; i >= 0; i--) {
      BDD len = makeLength(i);

      // Add new prefixes that might hijack traffic
      BDD withLenOver = allOverPrefixes.and(len).exist(getLenBits());
      mightHijack = mightHijack.orWith(withLenOver);

      // Get the must reach prefixes for this length
      BDD withLen = underPfxOnly.and(len);
      if (withLen.isZero()) {
        continue;
      }

      // quantify out bits i+1 to 32
      BDD removeBits = removeBits(i);
      if (!removeBits.isOne()) {
        withLen = withLen.exist(removeBits);
      }

      // Remove the may hijack addresses
      withLen = withLen.andWith(mightHijack.not());

      // accumulate resulting headers
      reachablePackets = reachablePackets.orWith(withLen);
    }

    reachablePackets = reachablePackets.exist(getLenBits());
    return reachablePackets;
  }

  /*
   * Compute the set of BDD routeVariables to quantify away for a given prefix length.
   * For efficiency, these values are cached.
   */
  public BDD removeBits(int len) {
    BDD removeBits = _dstBitsCache.get(len);
    if (removeBits == null) {
      removeBits = _netFactory.one();
      for (int i = 31; i >= len; i--) {
        BDD x = _variables.getPrefix().getBitvec()[i];
        removeBits = removeBits.and(x);
      }
      _dstBitsCache.put(len, removeBits);
    }
    return removeBits;
  }

  /*
   * Make a BDD representing an exact prefix length match.
   * For efficiency, these results are cached for each prefix length.
   */
  public BDD makeLength(int i) {
    BDD len = _lengthCache.get(i);
    if (len == null) {
      BDDInteger pfxLen = _variables.getPrefixLength();
      BDDInteger newVal = new BDDInteger(pfxLen);
      newVal.setValue(i);
      len = _netFactory.one();
      for (int j = pfxLen.getBitvec().length; j >= 0; j--) {
        BDD var = pfxLen.getBitvec()[j];
        BDD val = newVal.getBitvec()[j];
        if (val.isOne()) {
          len = len.and(var);
        } else {
          len = len.andWith(var.not());
        }
      }
      _lengthCache.put(i, len);
    }
    return len;
  }

  /*
   * Computes the transitive closure of a fib
   */
  public BDD transitiveClosure(BDD fibs) {
    BDD fibsTemp = fibs.replace(getSrcToTempRouterSubstitution());
    Set<BDD> seenFibs = new HashSet<>();
    BDD newFib = fibs;
    do {
      seenFibs.add(newFib);
      newFib = newFib.replace(getDstToTempRouterSubstitution());
      newFib = newFib.and(fibsTemp);
      newFib = newFib.exist(getTempRouterBits());
    } while (!seenFibs.contains(newFib));
    return newFib;
  }

  public <T> BDD transitiveClosure(Map<String, AbstractFib<T>> fibs) {
    BDD allFibs = _netFactory.zero();
    for (Entry<String, AbstractFib<T>> e : fibs.entrySet()) {
      String router = e.getKey();
      AbstractFib<T> fib = e.getValue();
      BDD routerBdd = _variables.getSrcRouter().value(router);
      BDD annotatedFib = fib.getHeaderspace().and(routerBdd);
      allFibs = allFibs.orWith(annotatedFib);
    }
    return transitiveClosure(allFibs);
  }

  public List<RibEntry> toRoutes(BDD rib) {
    List<RibEntry> ribEntries = new ArrayList<>();
    List<SatAssignment> entries = BDDUtils.allSat(_netFactory, rib, true);
    for (SatAssignment entry : entries) {
      Prefix p = new Prefix(entry.getDstIp(), entry.getPrefixLen());
      RibEntry r =
          new RibEntry(
              entry.getSrcRouter(),
              p,
              entry.getRoutingProtocol(),
              entry.getMetric(),
              entry.getAdminDist(),
              entry.getNextHopIp().toString(),
              entry.getDstRouter());
      ribEntries.add(r);
    }
    return ribEntries;
  }

  /*
   * Getters
   */

  public BDDNetFactory getNetFactory() {
    return _netFactory;
  }

  public BDDRoute getVariables() {
    return _variables;
  }

  public BDDPairing getPairing() {
    return _pairing;
  }

  public Map<Integer, BDD> getLengthCache() {
    return _lengthCache;
  }

  public Map<Integer, BDD> getDstBitsCache() {
    return _dstBitsCache;
  }

  public BDD getLenBits() {
    return _lenBits;
  }

  public BDD getDstRouterBits() {
    return _dstRouterBits;
  }

  public BDD getAllQuantifyBits() {
    return _allQuantifyBits;
  }

  public BDD getModifiedBits() {
    return _modifiedBits;
  }

  public BDD getTempRouterBits() {
    return _tempRouterBits;
  }

  public BDDPairing getDstToTempRouterSubstitution() {
    return _dstToTempRouterSubstitution;
  }

  public BDDPairing getSrcToTempRouterSubstitution() {
    return _srcToTempRouterSubstitution;
  }

  public BDD getTransientBits() {
    return _transientBits;
  }
}
