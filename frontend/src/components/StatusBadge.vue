<script setup lang="ts">
defineProps<{ value: string; text?: string }>()
const labels: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  NORMAL: '正常',
  ABNORMAL: '异常',
  PROCESSED: '已处理',
  IGNORED_STALE: '忽略旧序号',
  IGNORED_SESSION: '忽略旧会话',
  IGNORED_DISABLED: '设备已停用',
  UNKNOWN: '待上报',
}
</script>

<template>
  <span class="status-badge" :class="value.toLowerCase()"
    ><span></span>{{ text ?? labels[value] ?? value }}</span
  >
</template>
