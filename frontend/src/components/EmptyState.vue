<script setup lang="ts">
import { Unplug } from 'lucide-vue-next'
defineProps<{ title: string; description: string }>()
</script>

<template>
  <div class="empty-state" role="status">
    <div class="empty-icon"><Unplug :size="32" :stroke-width="1.4" /></div>
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
  </div>
</template>
