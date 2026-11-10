import { addPluginListener, invoke } from "@tauri-apps/api/core";

export type JarvisSpeechEvent = { text?: string; state?: string; delivered?: boolean; reason?: string; code?: number };

export const jarvisVoice = {
  initialize: () => invoke<{ ready: boolean }>("plugin:jarvis-voice|initialize"),
  startBackend: () => invoke<{ started: boolean; reason?: string; runtimePath?: string }>("plugin:jarvis-voice|startBackend"),
  listenOnce: () => invoke<{ started: boolean; reason?: string }>("plugin:jarvis-voice|listenOnce"),
  stopListening: () => invoke<{ stopped: boolean }>("plugin:jarvis-voice|stopListening"),
  startRecording: () => invoke<{ started: boolean; reason?: string }>("plugin:jarvis-voice|startRecording"),
  stopRecording: () => invoke<{ stopped: boolean }>("plugin:jarvis-voice|stopRecording"),
  speak: (text: string) => invoke<{ accepted: boolean }>("plugin:jarvis-voice|speak", { text }),
  shutdown: () => invoke<{ shutdown: boolean }>("plugin:jarvis-voice|shutdown"),
  onSpeechResult: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechResult", callback),
  onSpeechState: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechState", callback),
  onSpeechDelivery: (callback: (event: JarvisSpeechEvent) => void) =>
    addPluginListener<JarvisSpeechEvent>("jarvis-voice", "speechDelivery", callback),
};
