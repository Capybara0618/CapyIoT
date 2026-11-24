<script setup lang="ts">
import { label } from '../video'
defineProps<{ value: string }>()
</script>
<template>
  <span
    class="value-badge"
    :class="{
      danger: ['FAULT', 'OFFLINE', 'ACTIVE'].includes(value),
      success: ['ONLINE', 'HEALTHY', 'CLOSED', 'RECOVERED', 'PASS'].includes(value),
    }"
    >{{ label(value) }}</span
  >
</template>
