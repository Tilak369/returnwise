# Deploying ReturnWise: a step-by-step guide

Everything below runs in **Google Cloud Shell**, a free terminal in your browser that already has `gcloud`, `firebase`, `git`, and Java installed. You don't need to install anything on your laptop.

**Time needed:** about 20 minutes, most of it spent waiting for the build.

---

## 1. Create the Google Cloud project (5 min)

1. Go to https://console.cloud.google.com and sign in.
2. If you have hackathon credits or a free trial, activate them first so billing is available.
3. Click the project picker at the top, choose **New project**, name it `returnwise-<yourname>`, and click **Create**.
4. Write down the **Project ID**. It's shown under the name and may have numbers added, for example `returnwise-tilak-482910`.
5. Make sure **billing is linked** under **Billing**, then **Link a billing account**. Cloud Run and Vertex AI won't work without it.

## 2. Put the code on GitHub (3 min)

1. Create a new **public** repository at https://github.com/new called `returnwise`. Don't add a README.
2. Open **Cloud Shell**, the `>_` icon at the top right of the Cloud Console.
3. Upload `returnwise.zip` using the **⋮ menu** in Cloud Shell, then **Upload**. Then run:

```bash
unzip returnwise.zip && cd returnwise
git branch -M main
git remote add origin https://github.com/<your-github-username>/returnwise.git
git push -u origin main
```

When git asks for a password, use a **GitHub personal access token**. Create one at GitHub, then **Settings**, then **Developer settings**, then **Tokens (classic)**, with the `repo` scope selected. Your normal GitHub password won't work here.

## 3. Log in to Firebase (1 min)

```bash
firebase login --no-localhost
```

Open the link it prints, approve access, then paste the code back into Cloud Shell.

## 4. Deploy (10 to 12 min)

```bash
chmod +x deploy.sh
./deploy.sh <your-project-id>
```

The script prints two URLs at the end:

- `https://returnwise-xxxxx-as.a.run.app`: the Cloud Run URL
- `https://<your-project-id>.web.app`: the Firebase Hosting URL. **Use this one in the submission form.**

## 5. Check that it works

1. Open the Firebase URL. The pill at the top right should say **"Gemini gemini-2.5-flash on Vertex AI · Firestore"**.
2. Click the **LL-10021** demo chip, then **Submit**. Within about 10 seconds you should see **Size swap approved** with a size 33 replacement.
3. Try **LL-10022** with a photo of any garment that has a seam, and **LL-10023**, which should go to manual review.
4. Open the **Operations** tab and click a case to see the full agent trace.

## If something goes wrong

| Symptom | Fix |
|---|---|
| `PERMISSION_DENIED` on Vertex AI | Wait 2 minutes after the first deploy, because IAM changes take time to apply. Then try again. |
| "AI agent is temporarily unavailable" | Run `gcloud run services logs read returnwise --region asia-southeast1 --limit 50` and look for the Gemini error. If the model name isn't available, redeploy with `GEMINI_MODEL=gemini-2.5-pro` or another listed model. |
| `firebase deploy` says the project isn't a Firebase project | Open https://console.firebase.google.com, click **Add project**, and pick your existing GCP project. Then rerun the last line of the script. |
| Billing error | Link a billing account to the project in step 1.5. |

## Keep it running during judging

The prototype has to work at any moment between **Oct 19 and Nov 6**. The deploy script keeps **one instance always warm** (`--min-instances 1`), so judges never wait for a cold start. Keep the project and billing active until the results are out, and don't delete anything.

To reset the demo stock levels at any time, run:

```bash
curl -X POST https://<your-project-id>.web.app/api/admin/reseed
```
