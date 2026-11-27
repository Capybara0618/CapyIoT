package io.capyiot.alarm.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.TreeSet;

/** Object key order is not a semantic change; array order remains meaningful. */
public final class CanonicalJson {
  private CanonicalJson() {}

  public static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      var result = JsonNodeFactory.instance.objectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      names.forEach(name -> result.set(name, sorted(node.get(name))));
      return result;
    }
    if (node.isArray()) {
      var result = JsonNodeFactory.instance.arrayNode();
      node.forEach(item -> result.add(sorted(item)));
      return result;
    }
    return node;
  }
}
