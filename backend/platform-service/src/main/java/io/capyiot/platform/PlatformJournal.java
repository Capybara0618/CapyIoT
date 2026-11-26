package io.capyiot.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Preserves the old canonical JSON/HMAC and table layout; its key is independent of service JWT rotation. */
@Component
public final class PlatformJournal {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;private final String hashKey;
    public PlatformJournal(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,@Value("${capyiot.platform.command-fingerprint-key}") String key) {
        if(key.length()<32)throw new IllegalArgumentException("Stable platform command fingerprint key is required");
        this.db=db;this.tx=tx;this.json=json;this.hashKey=key;
    }
    Optional<JsonNode> replay(String key,String actor,String type,Object command) {
        key(key);var rows=db.queryForList("SELECT request_hash,result_snapshot FROM command_request WHERE scope_id='PLATFORM' AND idempotency_key=?",key);
        if(rows.isEmpty())return Optional.empty();
        if(!fingerprint(Map.of("actor",actor,"type",type,"command",command)).equals(rows.getFirst().get("request_hash")))
            throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT","幂等键已用于不同命令");
        try{return Optional.of(json.readTree(rows.getFirst().get("result_snapshot").toString()));}
        catch(Exception error){throw new IllegalStateException("Invalid command receipt");}
    }
    JsonNode execute(String key,String actor,String type,Object command,Supplier<?> mutation) {
        key(key);String hash=fingerprint(Map.of("actor",actor,"type",type,"command",command));
        return tx.execute(status->{
            if(db.queryForList("SELECT scope_id FROM command_lock WHERE scope_id='PLATFORM' FOR UPDATE").isEmpty())throw ApiProblem.missing();
            var prior=replay(key,actor,type,command);if(prior.isPresent())return prior.get();
            JsonNode result=json.valueToTree(mutation.get());
            if(type.equals("user.create")&&result instanceof ObjectNode object)object.put("created",true);
            JsonNode stored=result.deepCopy();if(stored instanceof ObjectNode object){
                object.remove(java.util.List.of("password","secret","accessToken","deviceSecret","sessionToken"));
                if(type.equals("user.create"))object.put("created",false);
            }
            db.update("INSERT INTO command_request(scope_id,idempotency_key,actor_user_id,command_type,request_hash,result_snapshot) VALUES('PLATFORM',?,?,?,?,?)",
                    key,actor,type,hash,stored.toString());return result;
        });
    }
    String fingerprint(Object input) {
        try {var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(json.valueToTree(input)).toString().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException error){throw new IllegalStateException("Command fingerprint unavailable");}
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()){var sorted=json.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(n->sorted.set(n,canonical(node.get(n))));return sorted;}
        if(node.isArray()){var array=json.createArrayNode();node.forEach(n->array.add(canonical(n)));return array;}return node;
    }
    private static void key(String key){if(key==null||!key.matches("[\\x21-\\x7e]{1,128}"))throw ApiProblem.invalid("Idempotency-Key 无效");}
}
