<script setup lang="ts">
import PageHeader from '../components/PageHeader.vue'
</script>
<template>
  <div class="video-page">
    <PageHeader />
    <section class="video-panel">
      <h2>先建立设备事实，再处理运维任务</h2>
      <p>
        当前使用虚拟 IPC、NVR
        和交换机生成设备数据。它们按设备身份连接平台，使用与后续实体接入相同的消息边界；平台不接收视频画面，也不会控制设备。
      </p>
    </section>
    <div class="video-grid">
      <section class="video-panel">
        <h2>1. 注册与投入使用</h2>
        <p>在资产中心登记设备，保存一次性设备密钥，再将资产投入使用。设备的连接状态初始为未知。</p>
        <RouterLink to="/devices">前往资产中心</RouterLink>
      </section>
      <section class="video-panel">
        <h2>2. 建立真实关系</h2>
        <p>网络依赖、PoE 供电、录像通道分别登记。摄像机可更换，监控点位和历史绑定保留。</p>
        <RouterLink to="/topology">维护关系与点位</RouterLink>
      </section>
      <section class="video-panel">
        <h2>3. 启动虚拟设备</h2>
        <p>
          由运行环境启动设备模拟器。稳定场景持续上报；交换机故障场景暂停下游摄像机心跳，并由平台判断失联。
        </p>
        <p class="video-muted">在模拟器实际启动前，页面不会生成或伪造设备状态。</p>
      </section>
      <section class="video-panel">
        <h2>4. 根据证据处置</h2>
        <p>查看异常及关联线索，确认需要处理的故障，再创建维修任务。维修人与验收人分别履职。</p>
        <RouterLink to="/alarms">查看异常与故障</RouterLink>
      </section>
    </div>
    <section class="video-panel">
      <h2>连接与数据的区别</h2>
      <p>
        设备有心跳只表示连接在线。录像停止、端口断链或业务数据陈旧仍需处理。设备凭据轮换、停用或退役后，旧连接不能继续更新当前状态。
      </p>
    </section>
  </div>
</template>
