local deadline = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not deadline or tonumber(deadline) > tonumber(ARGV[2]) then return nil end
local version = redis.call('HGET', KEYS[1], 'version')
if redis.call('HGET', KEYS[1], 'status') ~= 'ONLINE' or not version then
    redis.call('ZREM', KEYS[2], ARGV[1])
    return nil
end
return tonumber(version)
