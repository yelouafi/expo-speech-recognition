import { requireNativeModule } from "expo";

import type { ExpoSpeechRecognitionModuleType } from "./ExpoSpeechRecognitionModule.types";

// It loads the native module object from the JSI or falls back to
// the bridge module (from NativeModulesProxy) if the remote debugger is on.
export const ExpoSpeechRecognitionModule =
  requireNativeModule<ExpoSpeechRecognitionModuleType>("ExpoSpeechRecognition");

const stop = ExpoSpeechRecognitionModule.stop;
const abort = ExpoSpeechRecognitionModule.abort;
const mute = ExpoSpeechRecognitionModule.mute;
const unmute = ExpoSpeechRecognitionModule.unmute;

ExpoSpeechRecognitionModule.abort = () => abort();
ExpoSpeechRecognitionModule.stop = () => stop();
ExpoSpeechRecognitionModule.mute = () => mute();
ExpoSpeechRecognitionModule.unmute = () => unmute();
