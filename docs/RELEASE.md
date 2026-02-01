# Release Guide

This document outlines the Play Store release process for the Android TV player.

## Play Store tracks

- **Internal testing**: fast iteration, small tester group.
- **Closed testing**: larger tester group, pre-production validation.
- **Open testing**: broader beta distribution (optional).
- **Production**: public release.

## Release flow

1. Build and sign the release bundle (AAB).
2. Upload to the Play Console in the desired track.
3. Configure staged rollout if releasing to Production.
4. Monitor crash reports and ANRs before expanding rollout.

## Staged rollout

Recommended default:

- Start at 5–10% for 24–48 hours.
- Increase to 25–50% if crash-free.
- Proceed to 100% once stability is confirmed.

## Rollback guidance

- Halt the rollout if crash rate spikes or playback regressions appear.
- Upload a hotfix bundle to the same track.
- Communicate changes and update release notes.
