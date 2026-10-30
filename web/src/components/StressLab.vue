<script setup lang="ts">
import { ref } from 'vue'
import { api, readableMessage } from '../api'
import { demoUsers, DEMO_PASSWORD, refreshAll, state, toast } from '../store'

const THREADS = 20
const running = ref(false)
const result = ref<{
  seat: string
  total: number
  won: number
  blocked: number
  elapsed: number
  orderNo: string
  reason: string
} | null>(null)

async function run() {
  const available = state.seats.filter(s => s.status === 'AVAILABLE')
  const target = state.seats.find(s => state.selected.includes(s.id)) ?? available[available.length - 1]
  if (!target) {
    toast('error', '没有可测试的座位', '请切换场次或等待锁定的座位释放')
    return
  }

  running.value = true
  result.value = null
  try {
    const tokens: string[] = []
    for (const u of demoUsers) {
      const res = await api('/api/user/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username: u.username, password: DEMO_PASSWORD })
      }, '')
      if (res.code === 200) tokens.push(res.data.accessToken)
    }

    const t0 = performance.now()
    const responses = await Promise.all(
      Array.from({ length: THREADS }, (_, i) =>
        api('/api/order/create', {
          method: 'POST',
          body: JSON.stringify({
            sessionId: state.sessionId,
            seatIds: [target.id],
            fastCloseDemo: true,
            simulateSeataRollback: false
          })
        }, tokens[i % tokens.length])
      )
    )
    const elapsed = Math.round(performance.now() - t0)
    const winners = responses.filter(r => r.code === 200)
    const losers = responses.filter(r => r.code !== 200)

    result.value = {
      seat: `${target.rowNo} 排 ${target.colNo} 座`,
      total: THREADS,
      won: winners.length,
      blocked: losers.length,
      elapsed,
      orderNo: winners[0]?.data?.orderNo ?? '—',
      reason: readableMessage(losers[0]?.message)
    }
    state.selected = []
    await refreshAll()
  } finally {
    running.value = false
  }
}
</script>

<template>
  <section class="card p-8 md:p-10">
    <div class="flex flex-col justify-between gap-6 md:flex-row md:items-end">
      <div class="max-w-xl">
        <p class="eyebrow">压力测试</p>
        <h3 class="mt-2 text-xl font-semibold tracking-tight">并发抢同一个座位</h3>
        <p class="mt-2 text-sm leading-relaxed text-ink-soft">
          {{ THREADS }} 个请求同时争抢一个座位。用户锁、座位联锁和数据库 CAS 三层防护，
          应当保证只有一个请求成功，其余全部被拦截。
        </p>
      </div>
      <button class="btn-ghost h-11 shrink-0 px-6" :disabled="running" @click="run">
        {{ running ? '测试中…' : '开始测试' }}
      </button>
    </div>

    <Transition enter-active-class="transition duration-300" enter-from-class="opacity-0 translate-y-1">
      <div v-if="result" class="mt-10 border-t border-line pt-8">
        <dl class="grid grid-cols-2 gap-y-8 md:grid-cols-4">
          <div>
            <dt class="text-xs text-ink-soft">并发请求</dt>
            <dd class="mt-2 text-4xl font-semibold tracking-tight tabular">{{ result.total }}</dd>
          </div>
          <div>
            <dt class="text-xs text-ink-soft">抢座成功</dt>
            <dd class="mt-2 text-4xl font-semibold tracking-tight text-sage tabular">{{ result.won }}</dd>
          </div>
          <div>
            <dt class="text-xs text-ink-soft">被拦截</dt>
            <dd class="mt-2 text-4xl font-semibold tracking-tight text-brick tabular">{{ result.blocked }}</dd>
          </div>
          <div>
            <dt class="text-xs text-ink-soft">总耗时</dt>
            <dd class="mt-2 text-4xl font-semibold tracking-tight tabular">
              {{ result.elapsed }}<span class="ml-1 text-base font-normal text-ink-soft">ms</span>
            </dd>
          </div>
        </dl>
        <p class="mt-8 text-xs leading-relaxed text-ink-soft">
          目标 {{ result.seat }}
          <span class="mx-2 text-line">/</span>成功订单 <span class="font-mono text-ink">{{ result.orderNo }}</span>
          <span class="mx-2 text-line">/</span>拦截原因：{{ result.reason }}
        </p>
      </div>
    </Transition>
  </section>
</template>
