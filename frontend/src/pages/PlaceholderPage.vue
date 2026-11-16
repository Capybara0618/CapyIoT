<script setup lang="ts">
import { useRoute } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'
const route = useRoute()
</script>

<template>
  <section>
    <div class="breadcrumb">园区运维<span>/</span>{{ route.meta.title }}</div>
    <header class="page-header">
      <div>
        <h1>{{ route.meta.title }}</h1>
        <p>{{ route.meta.description }}</p>
      </div>
    </header>
    <div class="placeholder-panel">
      <EmptyState
        title="业务功能待接入"
        description="当前页面已就绪，业务数据将在对应功能上线后展示。"
      />
    </div>
  </section>
</template>
