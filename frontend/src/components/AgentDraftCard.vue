<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AgentDraftReview from './AgentDraftReview.vue'
import { type AgentDraft, loadDraft, decideDraft } from '../agentDrafts'
import { allowed } from '../video'
const props = defineProps<{draftId:string}>()
const emit = defineEmits<{updated:[draft:AgentDraft]}>()
const draft=ref<AgentDraft|null>(null),error=ref(''),busy=ref(false),now=ref(Date.now())
const canConfirm=computed(()=>allowed('agent.draft.confirm').value && allowed('workorder.create').value)
const keys=new Map<string,string>()
let generation=0,attempts=0,poll:ReturnType<typeof setTimeout>|undefined,reading:AbortController|undefined
const clock=setInterval(()=>{now.value=Date.now()},1000)
async function refresh(){
  if(busy.value)return
  const current=++generation
  reading?.abort();reading=new AbortController()
  try{
    const view=await loadDraft(props.draftId,reading.signal)
    if(current!==generation)return
    const changed=draft.value?.status!==view.status || draft.value.execution?.status!==view.execution?.status
    draft.value=view;error.value=''
    if(changed)emit('updated',view)
    if(['CONFIRMED','EXECUTING'].includes(view.status) && attempts++<60){clearTimeout(poll);poll=setTimeout(()=>void refresh(),2000)}
  }catch(e){if(current===generation){draft.value=null;error.value=(e as Error).message}}
}
async function decide(action:'confirm'|'reject',reason?:string){
  if(busy.value||!draft.value)return
  const reviewed=draft.value,key=reviewed.draftId+':'+reviewed.revision+':'+action,current=++generation
  reading?.abort();clearTimeout(poll);busy.value=true;error.value=''
  if(!keys.has(key))keys.set(key,crypto.randomUUID())
  try{
    const view=await decideDraft(reviewed,action,keys.get(key)!,reason)
    if(current===generation){draft.value=view;emit('updated',view)}
  }catch(e){if(current===generation){draft.value=null;error.value=(e as Error).message}}
  finally{busy.value=false;attempts=0;if(current===generation&&!error.value)void refresh()}
}
watch(()=>props.draftId,()=>{++generation;clearTimeout(poll);attempts=0;draft.value=null;void refresh()},{immediate:true})
onBeforeUnmount(()=>{++generation;reading?.abort();clearTimeout(poll);clearInterval(clock)})
</script>
<template>
  <div class="agent-draft-card">
    <AgentDraftReview v-if="draft" :draft="draft" :busy="busy" :can-confirm="canConfirm" :now="now" @decide="decide" />
    <div v-else class="video-panel"><p v-if="error" class="video-error" role="alert">{{ error }}</p><p v-else role="status">正在核查工单草稿…</p><button :disabled="busy" @click="refresh">重新读取草稿</button></div>
  </div>
</template>
