<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { X } from 'lucide-vue-next'
import { state } from '../store'
import { money, parseList, posterTheme } from '../utils'

const order = computed(() => state.ticket)

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && state.ticket) state.ticket = null
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

/** 根据取票码生成确定性的二维码样式图案（演示用，非真实编码） */
const qr = computed(() => {
  const code = order.value?.ticketCode ?? ''
  const n = 25
  let seed = 0
  for (const ch of code) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0
  const rand = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0
    return seed / 0xffffffff
  }
  const inFinder = (x: number, y: number) =>
    (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
  const cells: [number, number][] = []
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (!inFinder(x, y) && rand() > 0.52) cells.push([x, y])
    }
  }
  return { n, cells, finders: [[0, 0], [n - 7, 0], [0, n - 7]] }
})
</script>

<template>
  <Transition
    enter-active-class="transition duration-200"
    enter-from-class="opacity-0"
    leave-active-class="transition duration-150"
    leave-to-class="opacity-0"
  >
    <div
      v-if="order"
      class="fixed inset-0 z-50 grid place-items-center bg-ink/45 p-4 backdrop-blur-sm"
      @click.self="state.ticket = null"
    >
      <div class="w-full max-w-[390px] overflow-hidden rounded-[28px] bg-white shadow-float">
        <div class="relative p-7 text-white" :style="{ background: posterTheme(order.showId) }">
          <button
            class="absolute right-4 top-4 grid h-8 w-8 place-items-center rounded-full text-white/70 transition hover:bg-white/10 hover:text-white"
            aria-label="关闭电子票"
            @click="state.ticket = null"
          >
            <X class="h-4 w-4" />
          </button>
          <p class="text-[11px] uppercase tracking-[0.16em] text-white/60">电子票 · E-Ticket</p>
          <h3 class="mt-4 pr-6 text-lg font-semibold leading-snug">{{ order.showTitle }}</h3>
          <p class="mt-2 text-xs text-white/75">{{ order.venueName }}</p>
        </div>

        <div class="p-7">
          <dl class="space-y-4 text-sm">
            <div class="flex items-baseline justify-between gap-4">
              <dt class="shrink-0 text-xs text-ink-mute">场次</dt>
              <dd class="text-right font-medium">{{ order.sessionTitle }}</dd>
            </div>
            <div class="flex items-baseline justify-between gap-4">
              <dt class="shrink-0 text-xs text-ink-mute">席位</dt>
              <dd class="text-right font-medium">{{ parseList(order.seatLabelsJson).join(' · ') }}</dd>
            </div>
            <div class="flex items-baseline justify-between gap-4">
              <dt class="shrink-0 text-xs text-ink-mute">实付</dt>
              <dd class="text-right font-semibold tabular">¥{{ money(order.totalAmount) }}</dd>
            </div>
          </dl>

          <div class="my-6 border-t border-dashed border-line" />

          <div class="flex flex-col items-center rounded-2xl bg-paper py-6">
            <svg :viewBox="`-1 -1 ${qr.n + 2} ${qr.n + 2}`" class="h-32 w-32" shape-rendering="crispEdges">
              <rect v-for="([x, y], i) in qr.cells" :key="i" :x="x" :y="y" width="1" height="1" fill="#1A1917" />
              <g v-for="([fx, fy], i) in qr.finders" :key="`f${i}`">
                <rect :x="fx" :y="fy" width="7" height="7" fill="#1A1917" />
                <rect :x="fx + 1" :y="fy + 1" width="5" height="5" fill="#FAF9F7" />
                <rect :x="fx + 2" :y="fy + 2" width="3" height="3" fill="#1A1917" />
              </g>
            </svg>
            <p class="mt-4 font-mono text-lg font-medium tracking-[0.2em]">{{ order.ticketCode }}</p>
            <p class="mt-1 font-mono text-[11px] text-ink-mute">{{ order.orderNo }}</p>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>
