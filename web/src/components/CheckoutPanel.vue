<script setup lang="ts">
import { computed } from 'vue'
import { X } from 'lucide-vue-next'
import ToggleRow from './ToggleRow.vue'
import type { SeatItem, ShowSession } from '../types'
import { money, zones } from '../utils'

const props = defineProps<{ seats: SeatItem[]; session?: ShowSession; submitting: boolean }>()
const emit = defineEmits<{ (e: 'remove', seat: SeatItem): void; (e: 'submit'): void }>()
const fastClose = defineModel<boolean>('fastClose', { default: true })
const rollback = defineModel<boolean>('rollback', { default: false })

const total = computed(() => props.seats.reduce((sum, s) => sum + Number(s.price), 0))
</script>

<template>
  <div class="card p-7">
    <div class="flex items-baseline justify-between">
      <h3 class="text-base font-semibold tracking-tight">已选座位</h3>
      <span class="text-xs text-ink-mute tabular">{{ seats.length }} / 4</span>
    </div>
    <p v-if="session" class="mt-1 text-xs text-ink-soft">{{ session.sessionTitle }}</p>

    <div class="mt-5 min-h-[124px]">
      <div
        v-if="!seats.length"
        class="grid h-[124px] place-items-center rounded-2xl border border-dashed border-line text-center text-sm leading-relaxed text-ink-mute"
      >
        在座位图中选择<br />最多 4 个座位
      </div>
      <TransitionGroup v-else tag="ul" class="divide-y divide-line" move-class="transition" enter-active-class="transition" enter-from-class="opacity-0">
        <li v-for="s in seats" :key="s.id" class="flex items-center justify-between py-3">
          <div class="flex items-center gap-3">
            <span class="h-2 w-2 rounded-full" :class="zones[s.zoneCode].dot" />
            <div>
              <p class="text-sm font-medium">{{ s.rowNo }} 排 {{ s.colNo }} 座</p>
              <p class="text-xs text-ink-mute">{{ zones[s.zoneCode].label }}</p>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <span class="text-sm tabular">¥{{ money(s.price) }}</span>
            <button
              class="grid h-6 w-6 place-items-center rounded-full text-ink-mute transition hover:bg-paper hover:text-ink"
              aria-label="移除"
              @click="emit('remove', s)"
            >
              <X class="h-3.5 w-3.5" />
            </button>
          </div>
        </li>
      </TransitionGroup>
    </div>

    <div class="mt-6 space-y-5 border-t border-line pt-6">
      <p class="eyebrow">演示选项</p>
      <ToggleRow v-model="fastClose" title="15 秒超时关单" description="未支付订单由死信队列自动关闭" />
      <ToggleRow v-model="rollback" tone="brick" title="模拟事务异常" description="锁座后抛出异常，验证 Seata 自动回滚" />
    </div>

    <div class="mt-6 border-t border-line pt-6">
      <div class="flex items-baseline justify-between">
        <span class="text-sm text-ink-soft">合计</span>
        <span class="text-[32px] font-semibold leading-none tracking-tight tabular">
          <span class="mr-0.5 text-lg font-normal text-ink-soft">¥</span>{{ money(total) }}
        </span>
      </div>
      <button
        class="btn-primary mt-6 h-12 w-full"
        :class="rollback ? '!bg-brick hover:!bg-[#9c3a22]' : ''"
        :disabled="!seats.length || submitting"
        @click="emit('submit')"
      >
        <template v-if="submitting">正在锁座…</template>
        <template v-else-if="rollback">提交并触发回滚</template>
        <template v-else>确认选座</template>
      </button>
      <p class="mt-3 text-center text-xs text-ink-mute">
        提交后座位将为你保留 {{ fastClose ? '15 秒' : '15 分钟' }}
      </p>
    </div>
  </div>
</template>
