# no11 音声認識 & AI応答アプリ

## 概要

no11は、マイクからの音声入力を自動検出し、音声認識（Djangoサーバー）とAI応答（Ollamaサーバー）を連携して返すデスクトップアプリです。

- 音声検出（VAD）で自動録音
- 録音→WAV保存→Django APIで音声認識
- 認識結果をOllama APIでAI応答
- 応答をGUIに表示＆Macの音声合成で読み上げ
- ボタン操作不要、完全自動

## 起動方法

1. 依存サーバーを起動
   - Djangoサーバー（音声認識API）
     ```sh
     python manage.py runserver
     ```
   - Ollamaサーバー（AI応答API）
     ```sh
     ollama serve
     ```
2. 本アプリを起動
   ```sh
   cd ipro/no11
   ./gradlew run
   ```

## 使い方
- アプリ起動で自動的にマイクがオンになり、音声入力を待ちます。
- 音声を検出すると自動で録音・認識・AI応答・表示・読み上げまで進みます。
- GUI下部に「マイク準備完了．音声待機中.....」などのステータスが表示されます。

## GitHub連携
- このリポジトリは [https://github.com/marutyan/ISP_llama](https://github.com/marutyan/ISP_llama) で管理されています。
- 変更後は `git add . && git commit -m "変更内容" && git push` で反映できます。

## ライセンス
- 本リポジトリは教育目的で作成されています。 