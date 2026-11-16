#!/bin/sh
# Checks sandbox-run against an S3 (MinIO): directories fetched, the command run without the transfer
# variables, writable directories sent back, its exit code kept; 125 when a transfer fails.
#   docker build -t factory-sandbox .. && transfer/check.sh
set -eu
image=${IMAGE:-factory-sandbox}
net=sandbox-run-check
work=$(mktemp -d)
trap 'docker rm -f sandbox-run-s3 >/dev/null 2>&1; docker network rm $net >/dev/null 2>&1; rm -rf "$work"' EXIT
docker network create $net >/dev/null
docker run -d --name sandbox-run-s3 --network $net -p 127.0.0.1::9000 minio/minio:latest server /data >/dev/null
s3=http://$(docker port sandbox-run-s3 9000/tcp | head -1)
admin() { curl -fsS --aws-sigv4 aws:amz:us-east-1:s3 --user minioadmin:minioadmin "$@"; }
until admin -o /dev/null "$s3/" 2>/dev/null; do sleep 1; done
# the bucket open to anyone: these URLs stand for presigned ones
admin -X PUT "$s3/runs" -o /dev/null
admin -X PUT "$s3/runs?policy" -d '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
  "Action":["s3:GetObject","s3:PutObject"],"Resource":["arn:aws:s3:::runs/*"]}]}' -o /dev/null

mkdir -p "$work/workspace" "$work/job"
echo before > "$work/workspace/a.txt"
echo '{"task":"t"}' > "$work/job/job.json"
tar -czf "$work/workspace.tgz" -C "$work/workspace" . && admin -T "$work/workspace.tgz" "$s3/runs/in/workspace.tgz" -o /dev/null
tar -czf "$work/job.tgz" -C "$work/job" . && admin -T "$work/job.tgz" "$s3/runs/in/job.tgz" -o /dev/null

url=http://sandbox-run-s3:9000/runs
sandbox() {
  docker run --rm --network $net --cap-drop ALL --read-only --user 1000:1000 \
    --tmpfs /tmp --tmpfs /workspace --tmpfs /job --tmpfs /out --entrypoint sandbox-run "$@"
}
code=0
sandbox -e SANDBOX_MOUNT_0_PATH=/workspace -e SANDBOX_MOUNT_0_GET=$url/in/workspace.tgz -e SANDBOX_MOUNT_0_PUT=$url/out/workspace.tgz \
  -e SANDBOX_MOUNT_1_PATH=/job -e SANDBOX_MOUNT_1_GET=$url/in/job.tgz \
  -e SANDBOX_MOUNT_2_PATH=/out -e SANDBOX_MOUNT_2_PUT=$url/out/out.tgz \
  "$image" sh -c 'echo after >> a.txt; cp /job/job.json /out/result.json; env | grep -c SANDBOX_MOUNT > /out/leaked; exit 3' \
  || code=$?
mkdir "$work/back" "$work/out"
admin "$s3/runs/out/workspace.tgz" | tar -xzf - -C "$work/back"
admin "$s3/runs/out/out.tgz" | tar -xzf - -C "$work/out"
echo "exit: $code, workspace: $(tr '\n' ' ' < "$work/back/a.txt"), result: $(cat "$work/out/result.json"), variables seen: $(cat "$work/out/leaked")"
[ "$code" = 3 ] && [ "$(cat "$work/back/a.txt")" = "before
after" ] && [ "$(cat "$work/out/result.json")" = '{"task":"t"}' ] && [ "$(cat "$work/out/leaked")" = 0 ]

missing=0
sandbox -e SANDBOX_MOUNT_0_PATH=/workspace -e SANDBOX_MOUNT_0_GET=$url/in/nothing.tgz "$image" sh -c 'echo ran' > "$work/missing" 2>&1 || missing=$?
lost=0
sandbox -e SANDBOX_MOUNT_0_PATH=/out -e SANDBOX_MOUNT_0_PUT=http://sandbox-run-s3:9000/no-bucket/out.tgz "$image" true > "$work/lost" 2>&1 || lost=$?
echo "input missing: $missing ($(cat "$work/missing")), output lost: $lost ($(cat "$work/lost"))"
[ "$missing" = 125 ] && ! grep -q ran "$work/missing" && [ "$lost" = 125 ] && echo OK
