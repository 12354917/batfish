package org.batfish.datamodel.questions;

import java.util.Set;
import java.util.TreeSet;

import org.batfish.common.BatfishException;
import org.batfish.datamodel.collections.NodeInterfacePair;
import org.batfish.datamodel.collections.NodeSet;
import org.codehaus.jettison.json.JSONObject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use=JsonTypeInfo.Id.CLASS, include=JsonTypeInfo.As.PROPERTY, property="class")
public abstract class Question {

   private static final String INTERFACE_BLACKLIST_VAR = "interface_blacklist";

   private static final String NODE_BLACKLIST_VAR = "node_blacklist";

   private final Set<NodeInterfacePair> _interfaceBlacklist;

   private final NodeSet _nodeBlacklist;

   private final QuestionType _type;

   public Question(QuestionType type) {
      _type = type;
      _nodeBlacklist = new NodeSet();
      _interfaceBlacklist = new TreeSet<NodeInterfacePair>();
   }

   public Question(QuestionType type, QuestionParameters parameters) {
      this(type);
      setParameters(parameters);
   }

   public abstract boolean getDataPlane();

   @JsonIgnore
   public boolean getDiffActive() {
      return !getDifferential()
            && (!_nodeBlacklist.isEmpty() || !_interfaceBlacklist.isEmpty());
   }

   public abstract boolean getDifferential();

   public Set<NodeInterfacePair> getInterfaceBlacklist() {
      return _interfaceBlacklist;
   }

   public NodeSet getNodeBlacklist() {
      return _nodeBlacklist;
   }

   @JsonIgnore
   public QuestionType getType() {
      return _type;
   }

   public void setJsonParameters(JSONObject parameters) {
      
   }
   
   public void setParameters(QuestionParameters parameters) {
      if (parameters.getTypeBindings().get(NODE_BLACKLIST_VAR) == VariableType.SET_STRING) {
         Set<String> nodeBlacklist = parameters
               .getStringSet(NODE_BLACKLIST_VAR);
         _nodeBlacklist.addAll(nodeBlacklist);
      }
      if (parameters.getTypeBindings().get(INTERFACE_BLACKLIST_VAR) == VariableType.SET_STRING) {
         Set<String> interfaceBlacklist = parameters
               .getStringSet(INTERFACE_BLACKLIST_VAR);
         for (String blacklistedInterfaceRaw : interfaceBlacklist) {
            String[] parts = blacklistedInterfaceRaw.split(":");
            if (parts.length != 2) {
               throw new BatfishException(
                     "Invalid interface blacklist line: \""
                           + blacklistedInterfaceRaw + "\"");
            }
            String node = parts[0];
            String iface = parts[1];
            NodeInterfacePair blacklistedInterface = new NodeInterfacePair(
                  node, iface);
            _interfaceBlacklist.add(blacklistedInterface);
         }
      }
   }
}
