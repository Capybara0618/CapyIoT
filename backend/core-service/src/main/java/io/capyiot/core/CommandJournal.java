package io.capyiot.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** A local transaction only. Each context constructs a journal using its own store. */
public final class CommandJournal {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String hashKey;
    private final boolean workOrders;
    public CommandJournal(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,String hashKey) { this(db,tx,json,hashKey,false); }
    public CommandJournal(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,String hashKey,boolean workOrders) { this.db=db; this.tx=tx; this.json=json; this.hashKey=hashKey;this.workOrders=workOrders; }
    private String table(){return workOrders?"work_order_request":"command_request";}
    private String scope(){return workOrders?"campus_id":"scope_id";}
    public JsonNode execute(String scope,String key,String actor,String type,Object command,Supplier<?> mutation) {
        if(key==null||!key.matches("[\\x21-\\x7e]{1,128}")) throw ApiProblem.invalid("Idempotency-Key 必须为 1–128 位可见 ASCII 字符");
        String hash=fingerprint(Map.of("actor",actor,"type",type,"command",command));
        return tx.execute(status->{
            if(db.queryForList("SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE",scope).isEmpty()) throw ApiProblem.missing();
            var existing=db.queryForList("SELECT request_hash,result_snapshot FROM "+table()+" WHERE "+scope()+"=? AND idempotency_key=?",scope,key);
            if(!existing.isEmpty()) {
                var prior=existing.getFirst();
                if(!hash.equals(prior.get("request_hash"))) throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT","幂等键已用于不同命令");
                return parse(prior.get("result_snapshot").toString());
            }
            JsonNode response=json.valueToTree(mutation.get()); JsonNode stored=response.deepCopy();
            // Registration/rotation can reveal a credential only once. It is never in a journal or event.
            if(stored instanceof ObjectNode object) object.remove(List.of("deviceSecret","secret","sessionToken","accessToken"));
            if(workOrders){
                long orderId=Long.parseLong(response.path("workOrderId").asText());
                String reservation=Set.of("WorkOrderCreated","WorkOrderReopened").contains(type)?db.queryForObject("SELECT last_reservation_id FROM work_order WHERE id=?",String.class,orderId):null;
                if(type.equals("WorkOrderCreated")&&stored instanceof ObjectNode object)object.put("created",false);
                db.update("INSERT INTO work_order_request(campus_id,idempotency_key,actor_user_id,command_type,request_hash,fingerprint,result_snapshot,order_id,reservation_id) VALUES(?,?,?,?,?,?,?,?,?)",scope,key,actor,type,hash,hash,stored.toString(),orderId,reservation);
            }
            else db.update("INSERT INTO command_request(scope_id,idempotency_key,actor_user_id,command_type,request_hash,result_snapshot) VALUES(?,?,?,?,?,?)",scope,key,actor,type,hash,stored.toString());
            return response;
        });
    }
    public Optional<JsonNode> replay(String scope,String key,String actor,String type,Object command){
        if(key==null||!key.matches("[\\x21-\\x7e]{1,128}"))throw ApiProblem.invalid("Idempotency-Key 无效");
        var existing=db.queryForList("SELECT request_hash,result_snapshot FROM "+table()+" WHERE "+scope()+"=? AND idempotency_key=?",scope,key);
        if(existing.isEmpty())return Optional.empty();var prior=existing.getFirst();
        if(!fingerprint(Map.of("actor",actor,"type",type,"command",command)).equals(prior.get("request_hash")))throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT","幂等键已用于不同命令");
        return Optional.of(parse(prior.get("result_snapshot").toString()));
    }
    public String fingerprint(Object object) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(json.valueToTree(object)).toString().getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            ObjectNode sorted=json.createObjectNode(); var names=new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name->sorted.set(name,canonical(node.get(name)))); return sorted;
        }
        if(node.isArray()) { ArrayNode array=json.createArrayNode(); node.forEach(item->array.add(canonical(item))); return array; }
        return node;
    }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored command result",e); } }
}
