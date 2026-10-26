# Lisa Prototype

Lisa Prototype is an experimental AI coding assistant built with Kotlin. It is designed to help inspect, read, search, edit, and manage project files in a local workspace.

## Features

- AI provider integration with OpenAI-compatible APIs
- Multi-provider configuration
- Workspace file listing, reading, searching, and tree inspection
- Focused file edits and previews
- ZIP project import with safety checks
- Workspace command execution
- Conversation storage
- Automated tests for core functionality

## Requirements

- Java 21 or compatible JDK
- Gradle
- `curl`
- An API key for a supported AI provider

## Build and test

Run these commands from the project directory:

```bash
gradle test
gradle build
