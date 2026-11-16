<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>
<template>
  <div class="breadcrumb">园区运维<span>/</span>{{ route.meta.title }}</div>
  <header class="page-header">
    <div>
      <h1>{{ route.meta.title }}</h1>
      <p>{{ route.meta.description }}</p>
    </div>
    <div class="page-actions"><slot /></div>
  </header>
</template>
