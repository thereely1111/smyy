<template>
  <div ref="root" class="ide-split" :class="[direction, { dragging }]" :style="sizes">
    <div class="first"><slot name="first" /></div>
    <div class="divider" role="separator" tabindex="0" :aria-label="label"
      :aria-orientation="direction === 'horizontal' ? 'vertical' : 'horizontal'" :aria-valuenow="Math.round(percent)" :aria-valuemin="min" :aria-valuemax="max"
      @pointerdown="start" @pointermove="move" @pointerup="end" @pointercancel="end" @lostpointercapture="end" @keydown="key" @dblclick="reset"><span /></div>
    <div class="second"><slot name="second" /></div>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
const props = withDefaults(defineProps<{ direction: 'horizontal' | 'vertical'; initial: number; min?: number; max?: number; storageKey: string; label: string }>(), { min: 15, max: 70 })
function stored() {
  try { const value = Number(localStorage.getItem(props.storageKey)); if (value >= props.min && value <= props.max) return value } catch {}
  return props.initial
}
const root = ref<HTMLElement>()
const percent = ref(stored())
const dragging = ref(false)
const sizes = computed(() => ({ '--pane-size': `${percent.value}%` }))
function save() { try { localStorage.setItem(props.storageKey, String(percent.value)) } catch {} }
function start(event: PointerEvent) { if (event.button !== 0) return; event.preventDefault(); dragging.value = true; (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId) }
function move(event: PointerEvent) {
  if (!dragging.value || !root.value) return
  const rect = root.value.getBoundingClientRect()
  const value = props.direction === 'horizontal' ? (event.clientX - rect.left) / rect.width : (event.clientY - rect.top) / rect.height
  percent.value = Math.max(props.min, Math.min(props.max, value * 100))
}
function end() { dragging.value = false; save() }
function reset() { percent.value = props.initial; save() }
function key(event: KeyboardEvent) {
  const previous = props.direction === 'horizontal' ? 'ArrowLeft' : 'ArrowUp'
  const next = props.direction === 'horizontal' ? 'ArrowRight' : 'ArrowDown'
  if (![previous, next, 'Home'].includes(event.key)) return
  event.preventDefault()
  percent.value = event.key === 'Home' ? props.initial : Math.max(props.min, Math.min(props.max, percent.value + (event.key === next ? 2 : -2)))
  save()
}
</script>
<style scoped>
.ide-split { display: grid; height: 100%; min-height: 0; min-width: 0; overflow: hidden; }
.horizontal { grid-template-columns: minmax(0, var(--pane-size)) 6px minmax(0, 1fr); }
.vertical { grid-template-rows: minmax(0, var(--pane-size)) 6px minmax(0, 1fr); }
.first, .second { min-height: 0; min-width: 0; overflow: hidden; }
.divider { display: flex; align-items: center; justify-content: center; background: var(--c-bg); touch-action: none; transition: background .18s; z-index: 2; }
.horizontal > .divider { cursor: col-resize; }
.vertical > .divider { cursor: row-resize; }
.divider:hover, .divider:focus-visible, .dragging > .divider { background: var(--c-line-strong); outline: none; }
.divider span { background: var(--c-muted); border-radius: 4px; width: 2px; height: 24px; transition: background .18s; }
.vertical > .divider span { width: 24px; height: 2px; }
.dragging { user-select: none; }
@media(max-width: 650px) { .horizontal { grid-template-columns: minmax(100px, var(--pane-size)) 6px minmax(0, 1fr); } }
</style>
