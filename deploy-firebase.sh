#!/usr/bin/env bash
# Deploys the no-billing (Firebase Spark plan) version of ReturnWise.
#   Usage (Cloud Shell):  ./deploy-firebase.sh <project-id>
# Prerequisites, once per project, in the Firebase console:
#   1. Firebase added to the Google Cloud project
#   2. Firebase AI Logic -> Get started -> Gemini Developer API
set -euo pipefail

PROJECT_ID="${1:?Usage: ./deploy-firebase.sh <project-id>}"
REGION="asia-southeast1"   # Singapore

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "==> Enabling Firestore and Firebase Hosting APIs"
gcloud services enable firestore.googleapis.com firebasehosting.googleapis.com

echo "==> Creating Firestore database (skipped if it exists)"
gcloud firestore databases create --location="${REGION}" --type=firestore-native 2>/dev/null \
  || echo "    Firestore database already exists"

echo "==> Registering the web app (skipped if one exists)"
if ! firebase apps:list WEB --project "${PROJECT_ID}" 2>/dev/null | grep -q "ReturnWise"; then
  firebase apps:create WEB "ReturnWise" --project "${PROJECT_ID}"
fi

echo "==> Seeding demo catalogue, customers and orders"
node web/seed.mjs "${PROJECT_ID}"

echo "==> Deploying Firebase Hosting and Firestore security rules"
firebase deploy --project "${PROJECT_ID}" --only hosting,firestore:rules

echo
echo "Done:  https://${PROJECT_ID}.web.app"
