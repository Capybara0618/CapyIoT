<script setup lang="ts">
import { computed } from 'vue'
import type { AgentRun } from '../agentChat'
import { evidenceLinks, visibleAnswer } from '../agentPresentation'
import { formatTime } from '../domain'
import { label } from '../video'
const props = defineProps<{ run: AgentRun }>()
const answer = computed(() => props.run.contentStatus === 'VISIBLE' ? visibleAnswer(props.run.answer, props.run.evidenceReferences) : null)
const sections = computed(() => answer.value ? [{title:'已核查事实',claims:answer.value.facts},{title:'分析与推断',claims:answer.value.inferences}] : [])
const evidenceNumber = (id:string) => props.run.evidenceReferences.findIndex(item=>item.evidenceId === id) + 1
</script>
<template>
  <div v-if="run.contentStatus !== 'VISIBLE'" class="video-notice" role="status">当前权限或可见数据已变化，这条历史回答与引用已隐藏。可在当前授权范围内重新提问。</div>
  <div v-else-if="answer" class="agent-answer">
    <section v-for="section in sections.filter(item=>item.claims.length)" :key="section.title">
      <h3>{{ section.title }}</h3>
      <ul><li v-for="(claim,index) in section.claims" :key="index"><span>{{ claim.statement }}</span><a v-for="id in claim.evidenceIds" :key="id" class="agent-citation" :href="'#evidence-'+run.runId+'-'+id" :aria-label="'查看证据 '+evidenceNumber(id)">[{{ evidenceNumber(id) }}]</a></li></ul>
    </section>
    <section v-if="answer.recommendations.length"><h3>建议操作</h3><ul><li v-for="(item,index) in answer.recommendations" :key="index">{{ item }}</li></ul></section>
    <section v-if="answer.unknowns.length"><h3>仍需核查</h3><ul><li v-for="(item,index) in answer.unknowns" :key="index">{{ item }}</li></ul></section>
    <details v-if="run.evidenceReferences.length" class="agent-evidence" open>
      <summary>来源证据 · {{ run.evidenceReferences.length }}</summary>
      <article v-for="(evidence,index) in run.evidenceReferences" :id="'evidence-'+run.runId+'-'+evidence.evidenceId" :key="evidence.evidenceId">
        <p><strong>[{{ index+1 }}]</strong> {{ evidence.summary }}</p><small>{{ formatTime(evidence.retrievedAt) }} · {{ label(evidence.freshnessStatus) }}</small>
        <div class="agent-evidence-links"><RouterLink v-for="link in evidenceLinks(evidence)" :key="link.path+JSON.stringify(link.query)" :to="{path:link.path,query:link.query}">{{ link.label }}</RouterLink></div>
      </article>
    </details>
  </div>
  <p v-else-if="run.answer" class="video-notice">回答或引用格式不完整，暂不展示。请刷新运行状态核查。</p>
</template>
