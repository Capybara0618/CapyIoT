local incomingVersion = tonumber(ARGV[7])
local version = tonumber(redis.call('HGET', KEYS[1], 'version') or '-1')
local epoch = tonumber(redis.call('HGET', KEYS[1], 'sessionEpoch') or '-1')
local seq = tonumber(redis.call('HGET', KEYS[1], 'seq') or '-1')
if version > incomingVersion then return 0 end
if epoch > tonumber(ARGV[3]) then return 0 end
if epoch == tonumber(ARGV[3]) and seq > tonumber(ARGV[4]) then return 0 end
redis.call('HSET', KEYS[1], 'status', ARGV[1], 'health', ARGV[2],
    'sessionEpoch', ARGV[3], 'seq', ARGV[4], 'lastHeartbeat', ARGV[5], 'version', ARGV[7])
if tonumber(ARGV[5]) > 0 and ARGV[1] == 'ONLINE' then
    redis.call('ZADD', KEYS[2], ARGV[6], ARGV[8])
else
    redis.call('ZREM', KEYS[2], ARGV[8])
end
return 1
