<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import AppHeader from './components/AppHeader.vue'
import ToastHost from './components/ToastHost.vue'
import TicketModal from './components/TicketModal.vue'
import MessagesDrawer from './components/MessagesDrawer.vue'
import TheaterView from './views/TheaterView.vue'
import OrdersView from './views/OrdersView.vue'
import TelemetryView from './views/TelemetryView.vue'
import { loadCatalog, loginAs, refreshAll, selectShow, state } from './store'
import { remainingSeconds } from './utils'

let timer: ReturnType<typeof setInterval> | undefined
let lastExpiredRefresh = 0

onMounted(async () => {
  await Promise.all([loadCatalog(), loginAs('zhaolan')])
  if (state.catalog.length) await selectShow(state.catalog[0].show.id)

  // 每秒驱动倒计时；有订单到期时节流刷新，等待死信消费者关单
  timer = setInterval(() => {
    state.now = Date.now()
    const expired = state.orders.some(
      o => o.status === 'UNPAID' && remainingSeconds(o.expireAt, state.now) === 0
    )
    if (expired && state.now - lastExpiredRefresh > 2000) {
      lastExpiredRefresh = state.now
      refreshAll()
    }
  }, 1000)
})

onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <AppHeader />

    <main class="mx-auto w-full max-w-[1200px] flex-1 px-6 pb-24 pt-12">
      <Transition
        mode="out-in"
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="opacity-0 translate-y-1"
        leave-active-class="transition duration-100"
        leave-to-class="opacity-0"
      >
        <TheaterView v-if="state.tab === 'theater'" />
        <OrdersView v-else-if="state.tab === 'orders'" />
        <TelemetryView v-else />
      </Transition>
    </main>

    <footer class="border-t border-line">
      <div class="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-3 px-6 py-8 text-xs text-ink-mute">
        <span>PulseTicket · 演出在线选座</span>
        <span>Spring Cloud Alibaba · Seata · RabbitMQ · Redisson · Vue 3</span>
      </div>
    </footer>

    <ToastHost />
    <TicketModal />
    <MessagesDrawer />
  </div>
</template>
