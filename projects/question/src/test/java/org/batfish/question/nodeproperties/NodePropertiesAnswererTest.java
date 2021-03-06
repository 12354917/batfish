package org.batfish.question.nodeproperties;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multiset;
import java.util.Map;
import java.util.Set;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.pojo.Node;
import org.batfish.datamodel.questions.NodePropertySpecifier;
import org.batfish.datamodel.table.ColumnMetadata;
import org.batfish.datamodel.table.Row;
import org.junit.Test;

public class NodePropertiesAnswererTest {

  @Test
  @SuppressWarnings("deprecation") // includes test of deprecated functionality
  public void getProperties() {
    String property1 = NodePropertySpecifier.CONFIGURATION_FORMAT;
    String property2 = NodePropertySpecifier.NTP_SERVERS;
    NodePropertySpecifier propertySpec = new NodePropertySpecifier(property1 + "|" + property2);

    Configuration conf1 = new Configuration("node1", ConfigurationFormat.CISCO_IOS);
    Configuration conf2 = new Configuration("node2", ConfigurationFormat.HOST);
    conf2.setNtpServers(ImmutableSortedSet.of("sa"));
    Map<String, Configuration> configurations = ImmutableMap.of("node1", conf1, "node2", conf2);
    Map<String, ColumnMetadata> columns =
        NodePropertiesAnswerer.createTableMetadata(new NodePropertiesQuestion(null, propertySpec))
            .toColumnMap();

    Set<String> nodes = ImmutableSet.of("node1", "node2");

    Multiset<Row> propertyRows =
        NodePropertiesAnswerer.getProperties(propertySpec, configurations, nodes, columns);

    // we should have exactly these two rows
    Multiset<Row> expected =
        HashMultiset.create(
            ImmutableList.of(
                Row.builder()
                    .put(NodePropertiesAnswerer.COL_NODE, new Node("node1"))
                    .put(property1, ConfigurationFormat.CISCO_IOS)
                    .put(property2, ImmutableList.of())
                    .build(),
                Row.builder()
                    .put(NodePropertiesAnswerer.COL_NODE, new Node("node2"))
                    .put(property1, ConfigurationFormat.HOST)
                    .put(property2, ImmutableList.of("sa"))
                    .build()));
    assertThat(propertyRows, equalTo(expected));

    // Using the legacy properties question
    NodePropertiesQuestion questionDeprecated = new NodePropertiesQuestion(null, null);
    questionDeprecated.setProperties(ImmutableList.of(property1, property2));
    propertyRows =
        NodePropertiesAnswerer.getProperties(propertySpec, configurations, nodes, columns);
    assertThat(propertyRows, equalTo(expected));
  }
}
