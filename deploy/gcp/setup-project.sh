#!/usr/bin/env bash
# One-time GCP project setup for deploying trading-platform to Cloud Run.
#
# NOT EXECUTED as part of this repo's build or CI — this is a reference script for standing up
# real infrastructure, requiring a real GCP project, billing account, and `gcloud` authenticated
# against it. Written and reasoned through carefully, but unverified against a live project (this
# environment has no GCP credentials). Review every value before running it.
#
# Usage:
#   export PROJECT_ID=my-project
#   export REGION=europe-west2
#   export GITHUB_REPO=your-org/trading-platform
#   ./setup-project.sh

set -euo pipefail

: "${PROJECT_ID:?Set PROJECT_ID to your GCP project id}"
: "${REGION:=europe-west2}"
: "${GITHUB_REPO:?Set GITHUB_REPO to owner/repo, e.g. your-org/trading-platform}"

gcloud config set project "$PROJECT_ID"

echo "== Enabling required APIs =="
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  redis.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com

echo "== Artifact Registry (Docker images) =="
gcloud artifacts repositories create trading-platform \
  --repository-format=docker \
  --location="$REGION" \
  --description="trading-platform application images" \
  || echo "Repository already exists, skipping."

echo "== Cloud SQL (managed Postgres) =="
gcloud sql instances create trading-platform-db \
  --database-version=POSTGRES_16 \
  --tier=db-custom-2-7680 \
  --region="$REGION" \
  --storage-auto-increase \
  --backup-start-time=03:00 \
  || echo "Instance already exists, skipping."
gcloud sql databases create trading --instance=trading-platform-db || true
gcloud sql users create trading --instance=trading-platform-db --password="$(openssl rand -base64 24)" || true
echo "NOTE: the generated password above is not saved anywhere — set it deliberately and store it"
echo "      in Secret Manager (see below) rather than relying on this script's random default."

echo "== Memorystore (managed Redis) =="
gcloud redis instances create trading-platform-redis \
  --size=1 \
  --region="$REGION" \
  --redis-version=redis_7_2 \
  || echo "Instance already exists, skipping."

echo "== Secret Manager (JWT secret, Gemini/Claude API keys, DB password) =="
for secret in trading-jwt-secret trading-gemini-api-key trading-claude-api-key trading-db-password; do
  gcloud secrets create "$secret" --replication-policy=automatic || echo "$secret already exists, skipping."
done
echo "Populate each secret's value with: gcloud secrets versions add <secret> --data-file=-"

echo "== Workload Identity Federation for GitHub Actions (no long-lived JSON key) =="
gcloud iam workload-identity-pools create github-actions \
  --location=global --display-name="GitHub Actions" || echo "Pool already exists, skipping."
gcloud iam workload-identity-pools providers create-oidc github-actions-provider \
  --location=global \
  --workload-identity-pool=github-actions \
  --display-name="GitHub OIDC" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='${GITHUB_REPO}'" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  || echo "Provider already exists, skipping."

gcloud iam service-accounts create trading-platform-deployer \
  --display-name="trading-platform CI/CD deployer" || echo "Service account already exists, skipping."

DEPLOYER_SA="trading-platform-deployer@${PROJECT_ID}.iam.gserviceaccount.com"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')

gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER_SA" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-actions/attribute.repository/${GITHUB_REPO}"

for role in roles/run.admin roles/artifactregistry.writer roles/iam.serviceAccountUser; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${DEPLOYER_SA}" --role="$role"
done

echo
echo "== Done. Set these as GitHub Actions repository variables: =="
echo "  GCP_PROJECT_ID=${PROJECT_ID}"
echo "  GCP_DEPLOY_SERVICE_ACCOUNT=${DEPLOYER_SA}"
echo "  GCP_WORKLOAD_IDENTITY_PROVIDER=projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-actions/providers/github-actions-provider"
