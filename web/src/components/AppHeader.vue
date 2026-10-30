<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Bell, Check, ChevronDown, Ticket } from 'lucide-vue-next'
import { demoUsers, loadOrders, loadProfile, loadTelemetry, loginAs, state } from '../store'
import { money } from '../utils'
import type { Tab } from '../types'

const menuOpen = ref(false)
const menuRef = ref<HTMLElement | null>(null)

const nav: { key: Tab; label: string }[] = [
  { key: 'theater', label: '演出' },
  { key: 'orders', label: '我的订单' },
  { key: 'telemetry', label: '系统观测' }
]

const unpaid = computed(() => state.orders.filter(o => o.status === 'UNPAID').length)
const unread = computed(() => state.messages.filter(m => !m.isRead).length)
const me = computed(() => demoUsers.find(u => u.username === state.username))

function go(tab: Tab) {
  state.tab = tab
  if (tab === 'orders') loadOrders()
  if (tab === 'telemetry') Promise.all([loadTelemetry(), loadProfile()])
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function switchUser(username: string) {
  menuOpen.value = false
  if (username !== state.username) await loginAs(username)
}

function onDocClick(e: MouseEvent) {
  if (menuRef.value && !menuRef.value.contains(e.target as Node)) menuOpen.value = false
}

onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <header class="sticky top-0 z-30 border-b border-line bg-paper/85 backdrop-blur-md">
    <div class="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-6">
      <div class="flex items-center gap-10">
        <button class="flex items-center gap-2.5" @click="go('theater')">
          <span class="grid h-8 w-8 place-items-center rounded-[10px] bg-ink">
            <Ticket class="h-4 w-4 text-paper" :stroke-width="2" />
          </span>
          <span class="text-[17px] font-semibold tracking-tight">PulseTicket</span>
        </button>

        <nav class="hidden items-center gap-1 md:flex">
          <button
            v-for="item in nav"
            :key="item.key"
            class="relative flex items-center gap-2 rounded-full px-4 py-2 text-sm transition"
            :class="state.tab === item.key ? 'bg-ink/[0.06] font-medium text-ink' : 'text-ink-soft hover:text-ink'"
            @click="go(item.key)"
          >
            {{ item.label }}
            <span
              v-if="item.key === 'orders' && unpaid"
              class="grid h-[18px] min-w-[18px] place-items-center rounded-full bg-brick px-1 text-[11px] font-medium text-white tabular"
            >
              {{ unpaid }}
            </span>
          </button>
        </nav>
      </div>

      <div class="flex items-center gap-1">
        <span v-if="state.user" class="mr-3 hidden text-sm text-ink-soft tabular sm:inline">
          {{ money(state.user.pointsBalance) }} 积分
        </span>

        <button
          class="relative grid h-9 w-9 place-items-center rounded-full text-ink-soft transition hover:bg-ink/[0.06] hover:text-ink"
          title="通知"
          @click="state.messagesOpen = true"
        >
          <Bell class="h-[18px] w-[18px]" :stroke-width="1.75" />
          <span v-if="unread" class="absolute right-2 top-2 h-2 w-2 rounded-full bg-brick ring-2 ring-paper" />
        </button>

        <div ref="menuRef" class="relative ml-1">
          <button
            class="flex items-center gap-2 rounded-full py-1 pl-1 pr-3 transition hover:bg-ink/[0.06]"
            @click="menuOpen = !menuOpen"
          >
            <span class="grid h-7 w-7 place-items-center rounded-full bg-ink text-xs font-medium text-white">
              {{ me?.name.slice(-1) }}
            </span>
            <span class="text-sm font-medium">{{ me?.name }}</span>
            <ChevronDown class="h-3.5 w-3.5 text-ink-mute" />
          </button>

          <Transition
            enter-active-class="transition duration-150 ease-out"
            enter-from-class="opacity-0 -translate-y-1"
            leave-active-class="transition duration-100 ease-in"
            leave-to-class="opacity-0 -translate-y-1"
          >
            <div
              v-if="menuOpen"
              class="absolute right-0 mt-2 w-60 overflow-hidden rounded-2xl border border-line bg-white p-1.5 shadow-float"
            >
              <p class="px-3 pb-1 pt-2 eyebrow">切换演示账号</p>
              <button
                v-for="u in demoUsers"
                :key="u.username"
                class="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition hover:bg-paper"
                @click="switchUser(u.username)"
              >
                <span class="grid h-8 w-8 place-items-center rounded-full bg-paper text-sm font-medium text-ink">
                  {{ u.name.slice(-1) }}
                </span>
                <span class="flex-1">
                  <span class="block text-sm font-medium">{{ u.name }}</span>
                  <span class="block text-xs text-ink-mute">{{ u.tier }}</span>
                </span>
                <Check v-if="u.username === state.username" class="h-4 w-4 text-ink" />
              </button>
            </div>
          </Transition>
        </div>
      </div>
    </div>

    <!-- Mobile navigation -->
    <nav class="flex items-center gap-1 border-t border-line px-4 py-2 md:hidden">
      <button
        v-for="item in nav"
        :key="item.key"
        class="flex flex-1 items-center justify-center gap-1.5 rounded-full py-1.5 text-xs transition"
        :class="state.tab === item.key ? 'bg-ink/[0.06] font-medium text-ink' : 'text-ink-soft'"
        @click="go(item.key)"
      >
        {{ item.label }}
        <span
          v-if="item.key === 'orders' && unpaid"
          class="grid h-4 min-w-[16px] place-items-center rounded-full bg-brick px-1 text-[10px] font-medium text-white tabular"
        >
          {{ unpaid }}
        </span>
      </button>
    </nav>
  </header>
</template>
