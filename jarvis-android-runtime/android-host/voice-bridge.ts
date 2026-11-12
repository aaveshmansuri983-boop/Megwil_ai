import { addPluginListener, invoke, requestPermissions } from "@tauri-apps/api/core";

export type JarvisSpeechEvent = { text?: string; state?: string; delivered?: boolean; reason?: string; code?: number };

export const jarvisVoice = {
  requestPermissions: () => requestPermissions("jarvis-voice"),
  initialize: () => invoke<{ ready: boolean }>("plugin:jarvis-voice|initialize"),
  startBackend: () => invoke<{ started: boolean; reason?: string; runtimePath?: string }>("plugin:jarvis-voice|start_backend"),
  listenOnce: () => invoke<{ started: boolean; reason?: string }>("plugin:jarvis-voice|listen_once"),
  stopListening: () => invoke<{ stopped: boolean }>("plugin:jarvis-voice|stop_listening"),
  startRecording: () => invoke<{ started: boolean; reason?: string }>("plugin:jarvis-voice|start_recording"),
  stopRecording: () => invoke<{ stopped: boolean }>("plugin:jarvis-voice|stop_recording"),
  speak: (text: string) => invoke<{ accepted: boolean }>("plugin:jarvis-voice|speak", { text }),
  shutdown: () => invoke<{ shutdown: boolean }>("plugin:jarvis-voice|shutdown"),
  onSpeechResult: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechResult", callback),
  onSpeechState: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechState", callback),
  onSpeechDelivery: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechDelivery", callback),
};
