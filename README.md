# Folio

Folio answers questions about uploaded PDFs, Excel workbooks, Word documents, text, and source code. The frontend and Java API run as separate services and can be deployed independently.

See the [backend README](backend/README.md) for request routing, processing steps, and how to tell whether a model generated the answer.

## Run locally

Build and start the API from the project root with JDK 17 or later and Maven 3.9:

```powershell
mvn -f .\backend\pom.xml package
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\start.ps1
```

In another terminal, start the frontend with Node.js 18 or later:

```powershell
node .\frontend\server.mjs
```

Open [Folio](http://127.0.0.1:4173/). The Java API listens at `http://127.0.0.1:8765`. Use Ctrl+C in each terminal to stop its service.

## Ask questions

Upload up to eight files of up to 5 MiB each. Supported formats include PDF with a text layer, Excel (`.xls` and `.xlsx`), Word (`.docx`), CSV, TSV, Markdown, plain text, and common programming languages. Files are limited to 100,000 extracted characters and 350 sections each. Folio marks truncated files in the sidebar. Excel formulas use cached workbook values.

Use names or terms from your files in your question. Open the numbered citations to see the supporting page, sheet, or line. Each question uses the current set of files. Folio uses recent questions to help interpret pronouns in follow-ups. Adding or removing files clears that context.

Folio holds extracted content and chat in browser memory. Reloading the page, clearing the workspace, or closing the tab discards it. The Java API processes requests in memory, with no database or retained uploads.

## Compare versions and analyze Excel

Upload two document versions and choose **Compare versions**. Select which file is earlier and which is revised. Folio compares the extracted text and highlights added, removed, edited, and moved passages. Optional AI explanations use a sample of the changes. Comparison handles up to 400 passages per version and works best when both documents have readable text and similar structure.

Upload an Excel workbook with column names in the first populated row. Ask for a sum, average, count, minimum, or maximum. Use exact column names with optional grouping by one column, a sheet name, and an equality filter. For example, ask **Sum Revenue by Region in Sales where Month = November**. Java calculates against the sheet rows. The answer shows how many rows contributed and examples with their row numbers. Use one data table per sheet and exclude any precomputed totals from the inputs to avoid counting them twice. Calculations require a complete upload and support up to 20,000 populated cells within the existing extraction limits.

## Local AI

The Windows backend launcher looks in the project’s `.local/` directory for `llama/llama-server.exe` and either `qwen2.5-1.5b-instruct-q4_k_m.gguf` or `qwen2.5-0.5b-instruct.gguf`. It starts the model server on loopback port 8766 with request logging and prompt caching disabled. It sends relevant excerpts to that model and shows them with the answer. Check the citations when using generated answers.

If the local model is unavailable, Folio returns matching excerpts. Use `backend/start.ps1 -ExcerptsOnly` to run that mode explicitly. Local inference uses no API key or metered AI API calls. To connect an existing private model server, set `FOLIO_MODEL_NAME` and `FOLIO_MODEL_URL` to its model ID and base URL. The server must implement `/v1/chat/completions`.

## Optional Groq AI

Save one unquoted `GROQ_API_KEY=...` line in `backend/.env`, then start or restart the Java service with `backend/start.ps1`. The file is excluded from Git and the backend Docker build context. For a direct JAR run or a deployment, set `GROQ_API_KEY` as a secret environment variable on the Java service.

When a key is present, Folio uses `openai/gpt-oss-20b` and sends the question and selected excerpts to Groq after best-effort masking. Folio masks filenames and detected identifiers, then restores recognized placeholders in the answer. Confidential prose and undetected identifiers can still be sent. Open **How it works** in the workspace to see the data flow. The header displays **Groq AI**. If generation fails or the quota is reached, Folio displays matching excerpts. Use `-LocalOnly` to use the local model or `-ExcerptsOnly` for source search without a model.

## Deploy independently

| Service | Build context | Runtime settings |
| --- | --- | --- |
| Java API | `backend/` | `FOLIO_BIND`, `PORT`, `FOLIO_FRONTEND_ORIGINS` |
| Browser frontend and static server | `frontend/` | `FRONTEND_HOST`, `FRONTEND_PORT`, `FOLIO_API_BASE` |

```sh
docker build -t folio-api ./backend
docker build -t folio-web ./frontend
```

Set the API’s `FOLIO_FRONTEND_ORIGINS` to the exact public frontend URL, without a trailing slash. Use a comma-separated list to allow multiple origins. Set the frontend’s `FOLIO_API_BASE` to the Java API’s browser-accessible URL. If you deploy to a static host, publish `index.html`, `styles.css`, `app.js`, and `config.js` and set the API URL in `config.js`.

For public hosting, use HTTPS, add access controls and request limits, and isolate file parsing from other services. Hosting may incur infrastructure costs.

## Free hosted demo

Folio can run as two separate projects under one personal Vercel Hobby account. The Container Runtime is in beta.

1. Import the Git repository into a Vercel backend project. Set its Root Directory to **backend**. The backend Vercel config selects the Container preset and builds the existing Dockerfile. Set **PORT** to **8765** so Vercel forwards requests to Spring Boot. Verify **/api/status** on the assigned production URL.
2. Import the same repository into a second Vercel project. Set its Root Directory to **frontend** and **FOLIO_API_BASE** to the backend's HTTPS production origin. The frontend builds to **dist**.
3. Set **FOLIO_FRONTEND_ORIGINS** in the backend project to the frontend's production origin without a trailing slash. Redeploy the backend and verify an upload, an Excel calculation, and a comparison from the frontend.

The hosted source-search mode supports uploads, matching passages, Excel calculations, and version comparison. It runs without an AI provider key. Use small demo files because Vercel Functions have a 4.5 MB default request-body limit, which also covers the extracted data sent with a question. [Container Runtime](https://vercel.com/docs/functions/runtimes/container) · [Hobby limits](https://vercel.com/docs/plans/hobby) · [Function request limits](https://vercel.com/docs/functions/limitations#request-body-size)

The Render Blueprint remains an alternative for hosting the Java backend separately from Vercel.
