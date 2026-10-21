<template><div ref="host" class="code-editor" /></template>
<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { basicSetup } from 'codemirror'
import { EditorView, keymap } from '@codemirror/view'
import { Compartment } from '@codemirror/state'
import { python } from '@codemirror/lang-python'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import { indentUnit } from '@codemirror/language'
const props = defineProps<{ modelValue: string; path: string; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; run: []; save: []; cursor: [line: number, column: number] }>()
const host = ref<HTMLElement>()
const language = new Compartment()
const access = new Compartment()
let editor: EditorView | undefined
const mode = () => /\.py$/i.test(props.path) ? python() : /\.json$/i.test(props.path) ? json() : /\.[cm]?[jt]sx?$/i.test(props.path) ? javascript({ typescript: /\.tsx?$/i.test(props.path), jsx: /\.[jt]sx$/i.test(props.path) }) : []
onMounted(() => {
  editor = new EditorView({ parent: host.value, doc: props.modelValue, extensions: [
    basicSetup, indentUnit.of('    '), language.of(mode()), access.of(EditorView.editable.of(!props.readonly)),
    keymap.of([{ key: 'Mod-Enter', run: () => { emit('run'); return true } }, { key: 'Mod-s', run: () => { emit('save'); return true } }]),
    EditorView.updateListener.of(update => {
      if (update.docChanged && update.state.doc.toString() !== props.modelValue) emit('update:modelValue', update.state.doc.toString())
      if (update.selectionSet || update.docChanged) {
        const position = update.state.selection.main.head
        const line = update.state.doc.lineAt(position)
        emit('cursor', line.number, position - line.from + 1)
      }
    }),
    EditorView.theme({
      '&': { height: '100%', fontSize: '13px', background: 'var(--c-surface)' },
      '.cm-scroller': { fontFamily: '"JetBrains Mono", "Cascadia Code", Consolas, monospace', lineHeight: '1.8', overflow: 'auto' },
      '.cm-content': { padding: '16px 0' },
      '.cm-gutters': { background: 'var(--c-bg)', color: 'var(--c-muted)', border: 'none', minWidth: '48px' },
      '.cm-activeLine, .cm-activeLineGutter': { background: 'var(--c-primary-soft)' },
      '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { background: 'var(--c-primary-soft)' },
      '&.cm-focused': { outline: 'none' },
      '.cm-line': { padding: '0 20px' },
    }),
  ] })
})
watch(() => props.modelValue, value => {
  if (editor && value !== editor.state.doc.toString()) editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})
watch(() => props.path, () => editor?.dispatch({ effects: language.reconfigure(mode()) }))
watch(() => props.readonly, value => editor?.dispatch({ effects: access.reconfigure(EditorView.editable.of(!value)) }))
onBeforeUnmount(() => editor?.destroy())
</script>
<style scoped>.code-editor { flex: 1; min-height: 0; min-width: 0; overflow: hidden; height: 100%; }</style>
