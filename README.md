# Mobile Agent

[English](README.md) | [简体中文](README.zh-CN.md)

Mobile Agent is an open-source AI chat and local-tool application for Android. It lets users access file, network, and device capabilities through ongoing conversations, with explicit approval before sensitive actions are performed.

> This project is in an early stage of development. Its APIs, data structures, and interactions may still change. Use it on a test device first, and do not use it for payments or account-security operations.

## In Action

![Mobile Agent controlling Slay the Spire on Android](docs/images/mobile-agent-slay-the-spire.png)

<table>
  <tr>
    <td align="center">
      <img src="docs/images/mobile-agent-skill-guided-control.png" width="280" alt="Skill-guided Android game control"><br>
      <sub>Skill-guided game control</sub>
    </td>
    <td align="center">
      <img src="docs/images/mobile-agent-model-settings.png" width="280" alt="OpenAI-compatible model service settings"><br>
      <sub>Configurable model services</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/images/mobile-agent-tool-controls.png" width="280" alt="Tool availability and permission controls"><br>
      <sub>Per-tool capability controls</sub>
    </td>
    <td align="center">
      <img src="docs/images/mobile-agent-skill-management.png" width="280" alt="Installed skill management"><br>
      <sub>Skill installation and management</sub>
    </td>
  </tr>
</table>

## Key Features

- Multi-turn conversations, context compression, and model usage statistics.
- Support for OpenAI-compatible model services with configurable endpoints, models, and API keys.
- Speech transcription through OpenAI-compatible and iFLYTEK-compatible services.
- Reading and managing files, images, webpages, and generated artifacts.
- Optional Root/accessibility-based device control, a floating assistant, and permission controls.

## Roadmap

The project is continuously being updated...

## Permissions and Data Boundaries

Mobile Agent may request access to the microphone, notifications, display-over-other-apps, startup after device boot, the installed-app list, installing updates, and accessibility services. Root access, accessibility services, screenshots, and external model calls are highly privileged capabilities and should only be used after the user explicitly enables or approves them.

Model requests, speech transcription, and webpage access may send user-selected content to the corresponding third-party services. Read the [Privacy Notice](PRIVACY.md) before installing or using the app.

## Project Documentation

- [Product Definition](docs/PRODUCT.md)
- [Design Guidelines](docs/DESIGN.md)
- [Technical Architecture](docs/ARCHITECTURE.md)
- [Implementation Plan](docs/PLAN.md)
- [Third-Party Notices](THIRD_PARTY_NOTICES.md)

The detailed engineering documents currently remain in Chinese.

## License

Mobile Agent's own source code is licensed under the [Apache License 2.0](LICENSE). Third-party components remain subject to their respective licenses. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) and the `licenses/` directory for details.
