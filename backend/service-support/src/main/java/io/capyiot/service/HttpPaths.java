package io.capyiot.service;

/** Keep security matching and MVC dispatch on the same path; resource IDs never require encoded path bytes. */
public final class HttpPaths {
    private HttpPaths(){}
    public static String canonical(String raw,String context) {
        if(raw==null||context==null||!raw.startsWith(context)||!raw.startsWith("/")||raw.contains("%")||raw.contains(";")
                ||raw.contains("\\")||raw.contains("//")||raw.matches(".*(?:^|/)\\.{1,2}(?:/|$).*")||raw.chars().anyMatch(Character::isISOControl))
            throw ServiceFailure.denied(400,"REQUEST_PATH_INVALID");
        String path=raw.substring(context.length());
        if(!path.startsWith("/"))throw ServiceFailure.denied(400,"REQUEST_PATH_INVALID");
        return path;
    }
}
