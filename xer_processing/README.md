# XER Processing

This folder is the isolated Primavera P6/XER lane for this repository.

It is intentionally separate from the Modum workspace flow so the P6 ingestion,
baseline handling, and progress-measurement logic can evolve without mixing
with CxAlloy / Facility Grid data models.

## Current workflow scaffold

1. Select the `primavera_p6` data source.
2. Create a new XER project workspace.
3. Register and process a baseline XER file.
4. Capture the progress-measurement selection using `rsrc_name` and `rsrc_type`.
5. Continue into downstream schedule/progress logic in later iterations.

## Folder layout

- `python/Baseline_Workout.py`
  The notebook-style baseline processor provided by the user.
- `python/requirements.txt`
  Initial Python dependency list for the XER processor.

## Backend separation

The Spring backend namespace for this lane lives under:

`backend/src/main/java/com/cxalloy/integration/xerprocessing`

That module owns:

- project setup
- baseline import registration
- progress-measurement configuration
- normalized Primavera entities/tables for activities, resources, calendars,
  relationships, activity codes, and baseline distributions

## Database tables

The current scaffold creates dedicated P6 tables with the `xer_processing_`
prefix so they stay logically separate from the Modum workspace tables.

Core orchestration tables:

- `xer_processing_projects`
- `xer_processing_import_sessions`
- `xer_processing_progress_measurement_configs`

Normalized XER tables:

- `xer_processing_activities`
- `xer_processing_task_resources`
- `xer_processing_resources`
- `xer_processing_calendars`
- `xer_processing_activity_codes`
- `xer_processing_task_activity_codes`
- `xer_processing_task_predecessors`
- `xer_processing_calendar_workdays`
- `xer_processing_calendar_exceptions`
- `xer_processing_baseline_distributions`

## Notes

- The current backend endpoints register workflow state and table structure.
- The actual Python execution bridge is intentionally not wired yet.
- The next step after this scaffold is to define how baseline uploads move from
  storage into the processor and how the parsed rows are persisted.
