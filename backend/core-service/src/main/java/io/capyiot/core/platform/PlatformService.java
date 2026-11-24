package io.capyiot.core.platform;

import io.capyiot.core.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.validation.constraints.*;

@Service
public class PlatformService implements io.capyiot.core.ports.UserAssignmentPort {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectProvider<AssetScopePort> assets;
    private final Pbkdf2PasswordEncoder passwords=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash=passwords.encode(UUID.randomUUID().toString());

    public PlatformService(JdbcTemplate db,TransactionTemplate tx,ObjectProvider<AssetScopePort> assets) {
        this.db=db; this.tx=tx; this.assets=assets;
    }
    public record LoginCommand(String loginName,String password) {}
    public record SessionIdentity(String userId,String loginName,String displayName,long authorizationVersion,
                                  String sessionId,String jti,Instant expiresAt,List<String> roleCodes) {}
    public SessionIdentity login(LoginCommand command) {
        if(command.loginName()==null||command.password()==null||command.loginName().length()>64||command.password().length()>256)
            throw ApiProblem.invalid("登录名和密码不能为空或超长");
        SessionIdentity result=tx.execute(status->{
            var users=db.queryForList("SELECT * FROM app_user WHERE login_name=? FOR UPDATE",command.loginName());
            if(users.isEmpty()) { passwords.matches(command.password(),dummyHash); return null; }
            var user=users.getFirst(); String id=user.get("user_id").toString();
            var credential=db.queryForMap("SELECT * FROM user_credential WHERE user_id=? FOR UPDATE",id);
            boolean matches=passwords.matches(command.password(),credential.get("password_hash").toString());
            Instant now=Instant.now();
            Timestamp locked=(Timestamp)credential.get("locked_until");
            if(!matches||!user.get("status").equals("ACTIVE")||(locked!=null&&locked.toInstant().isAfter(now))) {
                if(!matches) db.update("UPDATE user_credential SET failed_attempt_count=failed_attempt_count+1, locked_until=CASE WHEN failed_attempt_count>=5 THEN ? ELSE locked_until END WHERE user_id=?",Timestamp.from(now.plusSeconds(300)),id);
                audit(null,"auth.login", "USER",id,"DENIED",null,null,"凭证无效或账号不可用");
                return null;
            }
            db.update("UPDATE user_credential SET failed_attempt_count=0,locked_until=NULL WHERE user_id=?",id);
            String sid=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString(); Instant expiry=now.plusSeconds(3600);
            db.update("INSERT INTO login_session(session_id,user_id,expires_at,jti) VALUES(?,?,?,?)",sid,id,Timestamp.from(expiry),jti);
            var identity=new SessionIdentity(id,command.loginName(),user.get("display_name").toString(),((Number)user.get("authorization_version")).longValue(),sid,jti,expiry,grants(id).stream().map(AuthorizationContext.Grant::roleCode).distinct().toList());
            audit(new AuthorizationContext(id,identity.loginName(),identity.displayName(),identity.authorizationVersion(),sid,List.of()),"auth.login","USER",id,"SUCCESS",null,null,null);
            return identity;
        });
        if(result==null) throw new ApiProblem(401,"LOGIN_FAILED","账号或密码错误，或账号暂不可用");
        return result;
    }

    public AuthorizationContext current(Jwt jwt) {
        String sid=jwt.getClaimAsString("sid"); Object claim=jwt.getClaim("authz_ver");
        if(sid==null||sid.isBlank()||!(claim instanceof Number version)||version.longValue()<1||jwt.getId()==null) throw invalidToken();
        var rows=db.queryForList("SELECT u.*,s.session_id FROM app_user u JOIN login_session s ON s.user_id=u.user_id WHERE u.user_id=? AND s.session_id=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP(3) AND u.status='ACTIVE'",jwt.getSubject(),sid,jwt.getId());
        if(rows.isEmpty()) throw invalidToken();
        var user=rows.getFirst(); long current=((Number)user.get("authorization_version")).longValue();
        if(current!=version.longValue()) throw new ApiProblem(401,"AUTHORIZATION_CHANGED","授权已变更，请重新登录");
        return new AuthorizationContext(jwt.getSubject(),user.get("login_name").toString(),user.get("display_name").toString(),current,sid,grants(jwt.getSubject()));
    }
    private ApiProblem invalidToken() { return new ApiProblem(401,"SESSION_INVALID","登录会话已失效"); }
    List<AuthorizationContext.Grant> grants(String userId) {
        // Materialize before fetching child rows. A RowMapper that borrows another connection
        // can exhaust the pool when every concurrent request holds its parent ResultSet open.
        var rows=db.queryForList("SELECT * FROM user_access_grant WHERE user_id=? AND status='ACTIVE'",userId);
        return rows.stream().map(row->new AuthorizationContext.Grant(
                row.get("grant_id").toString(),row.get("role_code").toString(),row.get("scope_type").toString(),(String)row.get("park_id"),
                db.queryForList("SELECT root_area_id FROM user_access_grant_area WHERE grant_id=? ORDER BY root_area_id",String.class,row.get("grant_id")),(String)row.get("assignment_relation"))).toList();
    }
    public Map<String,Object> me(AuthorizationContext actor) {
        return Map.of("userId",actor.userId(),"loginName",actor.loginName(),"displayName",actor.displayName(),"status","ACTIVE",
                "authorizationVersion",actor.authorizationVersion(),"roleCodes",actor.roles(),"permissionCodes",actor.permissions(),"scopeSummaries",actor.grants());
    }
    public void logout(Jwt jwt) {
        tx.executeWithoutResult(status->{
            String sid=jwt.getClaimAsString("sid");
            var sessions=db.queryForList("SELECT session_id FROM login_session WHERE session_id=? AND user_id=? AND jti=? FOR UPDATE",sid,jwt.getSubject(),jwt.getId());
            if(sessions.isEmpty()) throw invalidToken();
            int changed=db.update("UPDATE login_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason='LOGOUT' WHERE session_id=? AND status='ACTIVE'",sid);
            if(changed>0) audit(new AuthorizationContext(jwt.getSubject(),"","",((Number)jwt.getClaim("authz_ver")).longValue(),sid,List.of()),"auth.logout","SESSION",sid,"SUCCESS",null,null,null);
        });
    }
    public List<Map<String,Object>> users(AuthorizationContext actor) {
        actor.requirePlatform("platform.user.read");
        return db.queryForList("SELECT user_id AS userId,login_name AS loginName,display_name AS displayName,contact,status,authorization_version AS authorizationVersion,version FROM app_user ORDER BY login_name");
    }
    public Map<String,Object> user(AuthorizationContext actor,String id) {
        actor.requirePlatform("platform.user.read");
        var rows=db.queryForList("SELECT user_id AS userId,login_name AS loginName,display_name AS displayName,contact,status,authorization_version AS authorizationVersion,version FROM app_user WHERE user_id=?",id);
        if(rows.isEmpty()) throw ApiProblem.missing(); var result=rows.getFirst();result.put("grants",grants(id));return result;
    }
    @Override public void requireRoleForDevices(String userId,String role,Collection<io.capyiot.core.ports.DeviceQueryPort.DeviceRef> devices){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM app_user WHERE user_id=? AND status='ACTIVE'",Boolean.class,userId)))throw ApiProblem.invalid("责任人员账号不可用");
        var grants=grants(userId);
        if(devices.stream().anyMatch(device->grants.stream().noneMatch(grant->grant.roleCode().equals(role)&&Objects.equals(grant.parkId(),device.parkId())&&grant.scopeType().equals("ASSIGNED_TO_ME"))))throw ApiProblem.invalid("责任人员缺少对应园区的履职角色");
    }
    public record CreateUser(@NotBlank @Size(max=64) String loginName,@NotBlank @Size(min=6,max=256) String password,
                             @NotBlank @Size(max=100) String displayName,@Size(max=255) String contact) {}
    public Map<String,Object> createUser(AuthorizationContext actor,CreateUser command) {
        actor.requirePlatform("platform.user.manage");
        if(command.loginName()==null||!command.loginName().matches("[A-Za-z0-9_.@-]{1,64}")||command.password()==null||command.password().length()<6||command.password().length()>256||command.displayName()==null||command.displayName().isBlank()||command.displayName().length()>100) throw ApiProblem.invalid("用户资料无效；密码至少 6 位");
        return tx.execute(status->{
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO app_user(user_id,login_name,display_name,contact) VALUES(?,?,?,?)",id,command.loginName(),command.displayName(),command.contact());
            db.update("INSERT INTO user_credential(user_id,password_hash) VALUES(?,?)",id,passwords.encode(command.password()));
            audit(actor,"platform.user.create","USER",id,"SUCCESS",null,0L,null);
            return Map.of("userId",id,"loginName",command.loginName(),"displayName",command.displayName(),"version",0);
        });
    }
    public record UpdateUser(@Size(min=1,max=100) String displayName,@Size(max=255) String contact,String status,@NotNull @Min(0) Long expectedVersion) {}
    public void updateUser(AuthorizationContext actor,String id,UpdateUser command) {
        actor.requirePlatform("platform.user.manage");
        if(command.status()!=null&&!Set.of("ACTIVE","DISABLED","LOCKED").contains(command.status())) throw ApiProblem.invalid("账号状态无效");
        if(command.displayName()!=null&&(command.displayName().isBlank()||command.displayName().length()>100)) throw ApiProblem.invalid("显示名无效");
        tx.executeWithoutResult(status->{
            var users=db.queryForList("SELECT * FROM app_user WHERE user_id=? FOR UPDATE",id);
            if(users.isEmpty()) throw ApiProblem.missing();
            var before=users.getFirst(); if(((Number)before.get("version")).longValue()!=command.expectedVersion()) throw ApiProblem.conflict("用户资料已变更");
            boolean statusChange=command.status()!=null&&!command.status().equals(before.get("status"));
            db.update("UPDATE app_user SET display_name=COALESCE(?,display_name),contact=COALESCE(?,contact),status=COALESCE(?,status),version=version+1,authorization_version=authorization_version+?,updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=?",command.displayName(),command.contact(),command.status(),statusChange?1:0,id);
            if(statusChange) revokeSessions(id,"USER_STATUS_CHANGED");
            audit(actor,"platform.user.update","USER",id,"SUCCESS",command.expectedVersion(),command.expectedVersion()+1,null);
        });
    }
    public record GrantCommand(@NotBlank String roleCode,@NotBlank String scopeType,@Size(max=64) String parkId,
                               @Size(max=100) List<@NotBlank @Size(max=64) String> rootAreaIds,String assignmentRelation) {}
    public Map<String,Object> grant(AuthorizationContext actor,String userId,GrantCommand command) {
        actor.requirePlatform("platform.grant.assign");
        validateGrant(command);
        return tx.execute(status->{
            lockUser(userId);
            String id=insertGrant(userId,command,actor.userId());
            advanceAuthorization(userId);
            audit(actor,"platform.grant.create","GRANT",id,"SUCCESS",null,0L,null);
            return Map.of("grantId",id,"status","ACTIVE");
        });
    }
    private void validateGrant(GrantCommand c) {
        if(!Roles.PERMISSIONS.containsKey(c.roleCode())||!Set.of("NONE","PARK","AREA_SUBTREE","ASSIGNED_TO_ME").contains(c.scopeType())) throw ApiProblem.invalid("角色或数据范围无效");
        var roots=c.rootAreaIds()==null?List.<String>of():c.rootAreaIds();
        if(roots.stream().anyMatch(Objects::isNull)||roots.size()!=new HashSet<>(roots).size()) throw ApiProblem.invalid("区域根节点重复或无效");
        if(c.roleCode().equals("SYS_ADMIN")) {
            if(!c.scopeType().equals("NONE")||c.parkId()!=null||!roots.isEmpty()||c.assignmentRelation()!=null) throw ApiProblem.invalid("SYS_ADMIN 仅允许 NONE 范围");
            return;
        }
        if(c.scopeType().equals("NONE")||c.parkId()==null) throw ApiProblem.invalid("业务角色必须指定园区");
        boolean assigned=c.scopeType().equals("ASSIGNED_TO_ME");
        if(c.roleCode().equals("REPAIR_TECH")||c.roleCode().equals("ACCEPTANCE_STAFF")) {
            String relation=c.roleCode().equals("REPAIR_TECH")?"REPAIRER":"ACCEPTOR";
            if(!assigned||!relation.equals(c.assignmentRelation())||!roots.isEmpty()) throw ApiProblem.invalid("维修或验收角色仅允许本人指派范围");
        } else if(assigned||c.assignmentRelation()!=null) throw ApiProblem.invalid("运维角色不接受指派范围");
        if(c.scopeType().equals("AREA_SUBTREE")!=!roots.isEmpty()) throw ApiProblem.invalid("区域范围必须提供根区域，其他范围不能提供");
        AssetScopePort port=assets.getIfAvailable();
        if(port==null||!port.parkExists(c.parkId())||!port.areasBelongToPark(c.parkId(),roots)) throw ApiProblem.invalid("园区或区域不存在");
    }
    public void revokeGrant(AuthorizationContext actor,String id,String reason) {
        actor.requirePlatform("platform.grant.revoke"); requireReason(reason);
        tx.executeWithoutResult(status->{
            var ids=db.queryForList("SELECT user_id FROM user_access_grant WHERE grant_id=?",String.class,id);
            if(ids.isEmpty()) throw ApiProblem.missing(); String userId=ids.getFirst(); lockUser(userId);
            int changed=db.update("UPDATE user_access_grant SET status='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP(3),revoked_reason=?,version=version+1 WHERE grant_id=? AND status='ACTIVE'",actor.userId(),reason,id);
            if(changed>0) { advanceAuthorization(userId); audit(actor,"platform.grant.revoke","GRANT",id,"SUCCESS",null,null,reason); }
        });
    }
    private void lockUser(String id) { if(db.queryForList("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",id).isEmpty()) throw ApiProblem.missing(); }
    private void advanceAuthorization(String id) { db.update("UPDATE app_user SET authorization_version=authorization_version+1,version=version+1 WHERE user_id=?",id); revokeSessions(id,"AUTHORIZATION_CHANGED"); }
    private void revokeSessions(String id,String reason) { db.update("UPDATE login_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(3),revoked_reason=? WHERE user_id=? AND status='ACTIVE'",reason,id); }
    public List<Map<String,Object>> auditRecords(AuthorizationContext actor,int limit) {
        var grants=actor.require("platform.audit.read");
        boolean platform=grants.stream().anyMatch(g->g.roleCode().equals("SYS_ADMIN")&&g.scopeType().equals("NONE"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        return db.queryForList("SELECT audit_id AS auditId,actor_user_id AS actorUserId,actor_name_snapshot AS actorName,action_code AS actionCode,resource_type AS resourceType,resource_id AS resourceId,park_id AS parkId,result,source,reason,details_json AS details,created_at AS createdAt FROM audit_record ORDER BY audit_id DESC").stream().filter(row->{
            String park=(String)row.get("parkId");
            if(park==null)return platform;
            if(grants.stream().anyMatch(g->g.scopeType().equals("PARK")&&park.equals(g.parkId())))return true;
            var port=assets.getIfAvailable();if(port==null)return false;
            try {
                var details=json.readTree(Objects.toString(row.get("details"),"{}"));
                List<String> devices=new ArrayList<>(),areas=new ArrayList<>();
                details.path("deviceIds").forEach(id->devices.add(id.asText()));details.path("areaIds").forEach(id->areas.add(id.asText()));
                return port.auditVisible(actor,park,devices,areas);
            }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return false;}
        }).limit(Math.min(Math.max(limit,1),100)).map(row->{row.remove("details");return io.capyiot.core.Views.row(row);}).toList();
    }
    public void audit(AuthorizationContext actor,String action,String type,String id,String result,Long before,Long after,String reason) {
        db.update("INSERT INTO audit_record(actor_type,actor_id,actor_user_id,actor_name_snapshot,action_code,resource_type,resource_id,result,source,reason,before_version,after_version,session_id,authorization_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                actor==null?"SERVICE":"USER",actor==null?"platform-foundation":actor.userId(),actor==null?null:actor.userId(),actor==null?null:actor.displayName(),action,type,id,result,"API",reason,before,after,actor==null?null:actor.sessionId(),actor==null?null:actor.authorizationVersion());
    }
    public static void requireReason(String reason) { if(reason==null||reason.isBlank()||reason.length()>1000) throw ApiProblem.invalid("必须提供 1–1000 字的原因"); }
    private String insertGrant(String userId,GrantCommand c,String actorId) {
        String id=UUID.randomUUID().toString(); var roots=c.rootAreaIds()==null?List.<String>of():c.rootAreaIds().stream().sorted().toList();
        String canonical=String.join("|",c.scopeType(),Objects.toString(c.parkId(),""),String.join(",",roots),Objects.toString(c.assignmentRelation(),""));
        db.update("INSERT INTO user_access_grant(grant_id,user_id,role_code,scope_type,park_id,assignment_relation,scope_key_hash,granted_by) VALUES(?,?,?,?,?,?,?,?)",id,userId,c.roleCode(),c.scopeType(),c.parkId(),c.assignmentRelation(),hash(canonical),actorId);
        roots.forEach(root->db.update("INSERT INTO user_access_grant_area(grant_id,root_area_id) VALUES(?,?)",id,root));
        return id;
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public void seed(String adminHash,String operatorHash) {
        tx.executeWithoutResult(status->{
            seedAccount("admin","平台管理员",adminHash,"SYS_ADMIN","NONE",null,null);
            seedAccount("supervisor","运维主管",adminHash,"OPS_SUPERVISOR","PARK","CAMPUS-A",null);
            seedAccount("operator","运维人员",operatorHash,"OPS_OPERATOR","PARK","CAMPUS-A",null);
            seedAccount("repairer","维修人员",operatorHash,"REPAIR_TECH","ASSIGNED_TO_ME","CAMPUS-A","REPAIRER");
            seedAccount("acceptor","验收人员",operatorHash,"ACCEPTANCE_STAFF","ASSIGNED_TO_ME","CAMPUS-A","ACCEPTOR");
        });
    }
    private void seedAccount(String login,String name,String hash,String role,String scope,String park,String relation) {
        if(Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM app_user WHERE login_name=?",Boolean.class,login))) return;
        // Stable bootstrap IDs are identities, not credentials. Subsequent starts never restore revoked grants.
        String id="USER-"+login.toUpperCase(Locale.ROOT);
        db.update("INSERT INTO app_user(user_id,login_name,display_name) VALUES(?,?,?)",id,login,name);
        db.update("INSERT INTO user_credential(user_id,password_hash) VALUES(?,?)",id,hash);
        insertGrant(id,new GrantCommand(role,scope,park,List.of(),relation),"bootstrap");
        audit(null,"platform.bootstrap","USER",id,"SUCCESS",null,0L,null);
    }
}
