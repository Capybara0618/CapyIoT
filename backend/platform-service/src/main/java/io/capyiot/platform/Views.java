package io.capyiot.platform;

import java.util.*;

public final class Views {
    private Views(){}
    public static Map<String,Object> row(Map<String,Object> raw){
        Map<String,Object> view=new LinkedHashMap<>();raw.forEach((key,value)->{
            StringBuilder name=new StringBuilder();boolean upper=false;
            for(char c:key.toCharArray()){if(c=='_'){upper=true;continue;}name.append(upper?Character.toUpperCase(c):c);upper=false;}
            if(value instanceof java.sql.Timestamp||value instanceof java.time.LocalDateTime)value=DbTime.instant(value);
            if(name.toString().endsWith("Json")&&value instanceof String text){try{value=new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Invalid persisted JSON",invalid);}}
            view.put(name.toString(),value);
        });return view;
    }
    public static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    public static void version(Map<String,Object> row,long expected){if(version(row)!=expected)throw ApiProblem.conflict("记录已变更，请刷新后重试");}
    public static Map<String,Object> page(List<?> items,int page,int size){
        if(page<1||size<1||size>100)throw ApiProblem.invalid("分页范围无效");int start=(int)Math.min((long)(page-1)*size,items.size());
        return Map.of("items",items.subList(start,Math.min(start+size,items.size())),"total",items.size(),"page",page,"size",size);
    }
}
