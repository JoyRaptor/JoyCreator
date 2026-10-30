#!/usr/bin/env bash
# IdeaEngineTest — Joybot's suggestion tree, off device. Pure Java: only the androidx
# annotation stubs are needed, so this compiles in seconds and never touches Gradle.
#
# Usage:  bash tools/jvm-harness/run-ideas.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-ideas
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/ProjectFacts.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/Idea.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/Recipe.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/IdeaEngine.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/Outcome.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/Fillers.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/SlideFlow.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/HelpBook.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/TipBook.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/KeyCatcher.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/SetupTalk.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/WhatHere.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/AiProviders.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/ToolDocs.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/ToolRouter.java \
  app/src/main/java/com/fadcam/ui/faditor/slides/SlideContract.java \
  app/src/main/java/com/fadcam/ui/faditor/slides/SlideDesigns.java \
  tools/jvm-harness/IdeaEngineTest.java || exit 1
[ -f "$OUT/IdeaEngineTest.class" ] || { echo "no IdeaEngineTest class — compile did not run"; exit 1; }
java -cp "$OUT" IdeaEngineTest
