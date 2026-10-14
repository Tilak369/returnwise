#!/usr/bin/env bash
# One-shot deployment of ReturnWise to Google Cloud.
#   Usage:  ./deploy.sh <your-gcp-project-id>
# Needs: gcloud CLI (logged in), Firebase CLI (npm i -g firebase-tools, logged in), billing enabled on the project.
set -euo pipefail

PROJECT_ID="${1:?Usage: ./deploy.sh <gcp-project-id>}"
REGION="asia-southeast1"          # Singapore
SERVICE="returnwise"
SA="returnwise-sa@${PROJECT_ID}.iam.gserviceaccount.com"

echo "==> Using project ${PROJECT_ID}"
gcloud config set project "${PROJECT_ID}"

echo "==> Enabling APIs (Cloud Run, Cloud Build, Artifact Registry, Vertex AI, Firestore)"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  aiplatform.googleapis.com firestore.googleapis.com firebase.googleapis.com firebasehosting.googleapis.com

echo "==> Creating Firestore database (skipped if it already exists)"
gcloud firestore databases create --location="${REGION}" --type=firestore-native 2>/dev/null \
  || echo "    Firestore database already exists"

echo "==> Creating service account with least-privilege roles"
gcloud iam service-accounts create returnwise-sa --display-name="ReturnWise Cloud Run" 2>/dev/null \
  || echo "    Service account already exists"
for ROLE in roles/datastore.user roles/aiplatform.user; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" --member="serviceAccount:${SA}" \
    --role="${ROLE}" --condition=None --quiet >/dev/null
done

echo "==> Building and deploying to Cloud Run (takes a few minutes)"
gcloud run deploy "${SERVICE}" \
  --source . \
  --region "${REGION}" \
  --service-account "${SA}" \
  --allow-unauthenticated \
  --memory 1Gi \
  --cpu 1 \
  --min-instances 1 \
  --max-instances 5 \
  --set-env-vars "^|^GOOGLE_CLOUD_PROJECT=${PROJECT_ID}|GEMINI_LOCATION=global|GEMINI_MODEL=gemini-2.5-flash,gemini-3-flash-preview"

echo "==> Deploying Firebase Hosting (fronts Cloud Run) and Firestore security rules"
firebase projects:addfirebase "${PROJECT_ID}" 2>/dev/null || echo "    Firebase already enabled on project"
firebase deploy --config firebase.cloudrun.json --project "${PROJECT_ID}" --only hosting,firestore:rules

echo
echo "Done."
echo "  Cloud Run URL : $(gcloud run services describe ${SERVICE} --region ${REGION} --format='value(status.url)')"
echo "  Firebase URL  : https://${PROJECT_ID}.web.app"
