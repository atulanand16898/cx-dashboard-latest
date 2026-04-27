#!/usr/bin/env python3
import argparse
import csv
import json
import re
from datetime import datetime, date
from pathlib import Path


ENCODINGS = ("utf-8-sig", "utf-8", "cp1252", "latin-1")

TABLE_KEYS = {
    "PROJECT": "projects",
    "TASK": "activities",
    "TASKRSRC": "taskResources",
    "RSRC": "resources",
    "CALENDAR": "calendars",
    "ACTVCODE": "activityCodes",
    "TASKACTV": "taskActivityCodes",
    "TASKPRED": "taskPredecessors",
}

TABLE_NAMES = set(TABLE_KEYS)

# ── DCMA constants ────────────────────────────────────────────────────────────
HOURS_PER_WORK_DAY = 8.0
DCMA_MAX_DAYS = 44
DCMA_MAX_HOURS = DCMA_MAX_DAYS * HOURS_PER_WORK_DAY  # 352 hours

HARD_CONSTRAINT_TYPES = {"CS_MSO", "CS_MSF", "CS_MANDFIN", "CS_MANDSTART"}

# task_type values that are NOT schedulable work activities
NON_TASK_TYPES = {"TT_Mile", "TT_FinMile", "TT_LOE", "TT_WBS", "TT_Rsrc"}
# ─────────────────────────────────────────────────────────────────────────────


def parse_args():
    parser = argparse.ArgumentParser(description="Process a Primavera baseline XER file into JSON")
    parser.add_argument("--file", required=True, help="Path to the .xer file")
    parser.add_argument("--output", required=True, help="Path to the output JSON file")
    return parser.parse_args()


def normalize(value):
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def split_xer_columns(line):
    try:
        return next(csv.reader([line], delimiter="\t", quotechar='"'))
    except Exception:
        return line.split("\t")


def normalize_marker(value):
    return normalize(value.lstrip("\ufeff") if isinstance(value, str) else value)


def normalize_table_name(value):
    text = normalize_marker(value)
    return text.upper() if text else None


def parse_filename_metadata(file_path: Path):
    stem = file_path.stem
    match = re.match(r"^(?P<project_code>.+?)_(?P<data_date>\d{8})_(?P<revision>.+)$", stem)
    if not match:
        return {
            "projectCode": stem,
            "dataDate": None,
            "revisionLabel": None,
        }

    data_date = None
    try:
        data_date = datetime.strptime(match.group("data_date"), "%Y%m%d").date().isoformat()
    except ValueError:
        data_date = None

    return {
        "projectCode": normalize(match.group("project_code")),
        "dataDate": data_date,
        "revisionLabel": normalize(match.group("revision")),
    }


def read_text(file_path: Path):
    for encoding in ENCODINGS:
        try:
            return file_path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    raise ValueError(f"Unable to decode XER file with supported encodings: {file_path.name}")


def parse_xer_tables(file_path: Path):
    content = read_text(file_path)
    raw_tables = {table_name: [] for table_name in TABLE_NAMES}
    metadata = {"ermhdr": []}

    current_table = None
    current_headers = []

    for raw_line in content.splitlines():
        line = raw_line.rstrip("\r\n")
        if not line:
            continue

        parts = split_xer_columns(line)
        marker = normalize_table_name(parts[0] if parts else None)

        if marker == "ERMHDR":
            metadata["ermhdr"] = [normalize(part) for part in parts[1:]]
            continue

        if marker == "%T":
            current_table = normalize_table_name(parts[1] if len(parts) > 1 else None)
            current_headers = []
            continue

        if marker == "%F":
            current_headers = [normalize(part) for part in parts[1:]]
            continue

        if marker == "%R" and current_table and current_headers:
            values = parts[1:]
            if len(values) < len(current_headers):
                values = values + ([None] * (len(current_headers) - len(values)))

            row = {
                header: normalize(values[index]) if index < len(values) else None
                for index, header in enumerate(current_headers)
                if header
            }

            if current_table in raw_tables and row:
                raw_tables[current_table].append(row)
            continue

        if marker == "%E":
            current_table = None
            current_headers = []

    return raw_tables, metadata


def first_non_blank(*values):
    for value in values:
        if normalize(value):
            return normalize(value)
    return None


def enrich_metadata(metadata, project_rows, file_metadata):
    project_row = project_rows[0] if project_rows else {}
    project_code = first_non_blank(
        project_row.get("proj_short_name"),
        project_row.get("proj_id"),
        file_metadata["projectCode"],
    )
    return {
        "projectCode": project_code,
        "dataDate": file_metadata["dataDate"],
        "revisionLabel": file_metadata["revisionLabel"],
        "headerInfo": metadata.get("ermhdr", []),
    }


def map_activities(rows):
    return [
        {
            "externalTaskId": row.get("task_id"),
            "externalProjectId": row.get("proj_id"),
            "externalWbsId": row.get("wbs_id"),
            "externalCalendarId": row.get("clndr_id"),
            "primaryResourceId": row.get("rsrc_id"),
            "taskCode": row.get("task_code"),
            "taskName": row.get("task_name"),
            "taskType": row.get("task_type"),
            "durationType": row.get("duration_type"),
            "statusCode": row.get("status_code"),
            "completePctType": row.get("complete_pct_type"),
            "physCompletePct": row.get("phys_complete_pct"),
            "earlyStartDate": row.get("early_start_date"),
            "earlyEndDate": row.get("early_end_date"),
            "targetDurationHours": row.get("target_drtn_hr_cnt"),
            "remainingDurationHours": row.get("remain_drtn_hr_cnt"),
            # DCMA fields
            "totalFloatHours": row.get("total_float_hr_cnt"),
            "freeFloatHours": row.get("free_float_hr_cnt"),
            "constraintType": row.get("cstr_type"),
            "constraintDate": row.get("cstr_date"),
            "targetStartDate": row.get("target_start_date"),
            "targetEndDate": row.get("target_end_date"),
            "lateStartDate": row.get("late_start_date"),
            "lateEndDate": row.get("late_end_date"),
        }
        for row in rows
    ]


def map_resources(rows):
    return [
        {
            "externalResourceId": row.get("rsrc_id"),
            "parentResourceId": row.get("parent_rsrc_id"),
            "externalCalendarId": row.get("clndr_id"),
            "externalRoleId": row.get("role_id"),
            "resourceName": row.get("rsrc_name"),
            "resourceShortName": row.get("rsrc_short_name"),
            "resourceTitleName": row.get("rsrc_title_name"),
            "resourceType": row.get("rsrc_type"),
            "activeFlag": row.get("active_flag"),
            "loadTasksFlag": row.get("load_tasks_flag"),
            "levelFlag": row.get("level_flag"),
            "resourceNotes": row.get("rsrc_notes"),
        }
        for row in rows
    ]


def map_task_resources(rows, resources_by_id):
    mapped = []
    for row in rows:
        resource_ref = resources_by_id.get(row.get("rsrc_id"), {})
        mapped.append(
            {
                "externalTaskId": row.get("task_id"),
                "externalProjectId": row.get("proj_id"),
                "externalResourceId": row.get("rsrc_id"),
                "resourceName": row.get("rsrc_name") or resource_ref.get("rsrc_name"),
                "resourceShortName": row.get("rsrc_short_name") or resource_ref.get("rsrc_short_name"),
                "resourceType": row.get("rsrc_type") or resource_ref.get("rsrc_type"),
                "remainingQty": row.get("remain_qty"),
                "targetQty": row.get("target_qty"),
                "actualRegularQty": row.get("act_reg_qty"),
                "costPerQty": row.get("cost_per_qty"),
                "targetCost": row.get("target_cost"),
                "actualRegularCost": row.get("act_reg_cost"),
                "remainingCost": row.get("remain_cost"),
                "actualStartDate": row.get("act_start_date"),
                "actualEndDate": row.get("act_end_date"),
                "restartDate": row.get("restart_date"),
                "reendDate": row.get("reend_date"),
                "targetStartDate": row.get("target_start_date"),
                "targetEndDate": row.get("target_end_date"),
                "remainingLateStartDate": row.get("rem_late_start_date"),
                "remainingLateEndDate": row.get("rem_late_end_date"),
            }
        )
    return mapped


def map_calendars(rows):
    return [
        {
            "externalCalendarId": row.get("clndr_id"),
            "externalProjectId": row.get("proj_id"),
            "calendarName": row.get("clndr_name"),
            "calendarType": row.get("clndr_type"),
            "calendarData": row.get("clndr_data"),
        }
        for row in rows
    ]


def map_activity_codes(rows):
    return [
        {
            "externalActivityCodeId": row.get("actv_code_id"),
            "externalCodeTypeId": row.get("actv_code_type_id"),
            "codeType": row.get("actv_code_type"),
            "shortName": row.get("short_name"),
            "codeName": row.get("actv_code_name"),
            "sequenceNumber": row.get("seq_num"),
        }
        for row in rows
    ]


def map_task_activity_codes(rows):
    return [
        {
            "externalTaskId": row.get("task_id"),
            "externalProjectId": row.get("proj_id"),
            "externalActivityCodeId": row.get("actv_code_id"),
            "codeType": row.get("actv_code_type"),
            "codeValue": row.get("actv_code_name") or row.get("actv_code_id"),
        }
        for row in rows
    ]


def map_task_predecessors(rows):
    return [
        {
            "externalTaskId": row.get("task_id"),
            "externalProjectId": row.get("proj_id"),
            "externalPredecessorTaskId": row.get("pred_task_id"),
            "relationshipType": row.get("pred_type"),
            "lagHours": row.get("lag_hr_cnt"),
        }
        for row in rows
    ]


# ── DCMA Checkpoint Logic ─────────────────────────────────────────────────────

def safe_float(value):
    if value is None:
        return None
    try:
        return float(value)
    except (ValueError, TypeError):
        return None


def safe_date(value):
    if not value:
        return None
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%m/%d/%Y %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(str(value)[:len(fmt)], fmt).date()
        except (ValueError, TypeError):
            pass
    return None


def make_checkpoint(cp_id, name, violators, denominator, threshold=95.0):
    total_count = len(denominator)
    violating_count = len(violators)
    score = ((total_count - violating_count) / total_count * 100.0) if total_count > 0 else 100.0
    status = "PASS" if score >= threshold else "FAIL"
    return {
        "id": cp_id,
        "name": name,
        "status": status,
        "score": round(score, 2),
        "threshold": threshold,
        "violatingCount": violating_count,
        "totalCount": total_count,
        "exceptions": violators[:200],
    }


def compute_dcma_checkpoints(activities, task_predecessors, task_resources, data_date):
    data_dt = safe_date(data_date) if data_date else None

    # Categorise activities
    schedulable = [a for a in activities if a.get("taskType") not in NON_TASK_TYPES and a.get("externalTaskId")]

    # Build predecessor/successor membership sets
    tasks_with_pred = {r["externalTaskId"] for r in task_predecessors if r.get("externalTaskId")}
    tasks_with_succ = {r["externalPredecessorTaskId"] for r in task_predecessors if r.get("externalPredecessorTaskId")}

    # Tasks that have at least one resource row
    tasks_with_resources = {tr["externalTaskId"] for tr in task_resources if tr.get("externalTaskId")}

    checkpoints = []

    # ── CP 1: Logic ───────────────────────────────────────────────────────────
    logic_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "reason": (
                "Missing predecessor and successor"
                if a["externalTaskId"] not in tasks_with_pred and a["externalTaskId"] not in tasks_with_succ
                else ("Missing predecessor" if a["externalTaskId"] not in tasks_with_pred else "Missing successor")
            ),
        }
        for a in schedulable
        if a["externalTaskId"] not in tasks_with_pred or a["externalTaskId"] not in tasks_with_succ
    ]
    checkpoints.append(make_checkpoint(1, "Logic", logic_violators, schedulable))

    # ── CP 2: Leads (negative-lag relationships) ──────────────────────────────
    lead_violators = [
        {
            "activityId": r.get("externalTaskId"),
            "predecessorId": r.get("externalPredecessorTaskId"),
            "lagHours": r.get("lagHours"),
            "reason": f"Negative lag {r.get('lagHours')} hrs",
        }
        for r in task_predecessors
        if safe_float(r.get("lagHours")) is not None and safe_float(r.get("lagHours")) < 0
    ]
    checkpoints.append(make_checkpoint(2, "Leads", lead_violators, task_predecessors))

    # ── CP 3: Lags (positive-lag relationships) ───────────────────────────────
    lag_violators = [
        {
            "activityId": r.get("externalTaskId"),
            "predecessorId": r.get("externalPredecessorTaskId"),
            "lagHours": r.get("lagHours"),
            "reason": f"Positive lag {r.get('lagHours')} hrs",
        }
        for r in task_predecessors
        if safe_float(r.get("lagHours")) is not None and safe_float(r.get("lagHours")) > 0
    ]
    checkpoints.append(make_checkpoint(3, "Lags", lag_violators, task_predecessors))

    # ── CP 4: Relationship Types (SF relationships) ───────────────────────────
    sf_violators = [
        {
            "activityId": r.get("externalTaskId"),
            "predecessorId": r.get("externalPredecessorTaskId"),
            "relationshipType": r.get("relationshipType"),
            "reason": "Start-to-Finish (SF) relationship",
        }
        for r in task_predecessors
        if r.get("relationshipType") == "PR_SF"
    ]
    checkpoints.append(make_checkpoint(4, "Relationship Types", sf_violators, task_predecessors))

    # ── CP 5: Hard Constraints ────────────────────────────────────────────────
    constraint_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "constraintType": a.get("constraintType"),
            "reason": f"Hard constraint: {a.get('constraintType')}",
        }
        for a in schedulable
        if a.get("constraintType") in HARD_CONSTRAINT_TYPES
    ]
    checkpoints.append(make_checkpoint(5, "Hard Constraints", constraint_violators, schedulable))

    # ── CP 6: High Float (> 44 wd) ────────────────────────────────────────────
    incomplete = [a for a in schedulable if a.get("statusCode") != "TK_Complete"]
    high_float_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "totalFloatHours": a.get("totalFloatHours"),
            "reason": f"Total float {round(safe_float(a.get('totalFloatHours', 0)) / HOURS_PER_WORK_DAY, 1)} wd > {DCMA_MAX_DAYS} wd",
        }
        for a in incomplete
        if safe_float(a.get("totalFloatHours")) is not None and safe_float(a.get("totalFloatHours")) > DCMA_MAX_HOURS
    ]
    checkpoints.append(make_checkpoint(6, "High Float", high_float_violators, incomplete))

    # ── CP 7: Negative Float ──────────────────────────────────────────────────
    neg_float_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "totalFloatHours": a.get("totalFloatHours"),
            "reason": f"Negative float {round(safe_float(a.get('totalFloatHours', 0)) / HOURS_PER_WORK_DAY, 1)} wd",
        }
        for a in schedulable
        if safe_float(a.get("totalFloatHours")) is not None and safe_float(a.get("totalFloatHours")) < 0
    ]
    checkpoints.append(make_checkpoint(7, "Negative Float", neg_float_violators, schedulable))

    # ── CP 8: High Duration (> 44 wd) ─────────────────────────────────────────
    high_dur_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "targetDurationHours": a.get("targetDurationHours"),
            "reason": f"Duration {round(safe_float(a.get('targetDurationHours', 0)) / HOURS_PER_WORK_DAY, 1)} wd > {DCMA_MAX_DAYS} wd",
        }
        for a in schedulable
        if safe_float(a.get("targetDurationHours")) is not None and safe_float(a.get("targetDurationHours")) > DCMA_MAX_HOURS
    ]
    checkpoints.append(make_checkpoint(8, "High Duration", high_dur_violators, schedulable))

    # ── CP 9: Invalid Dates ───────────────────────────────────────────────────
    all_starts = [safe_date(a.get("earlyStartDate")) for a in activities if safe_date(a.get("earlyStartDate"))]
    all_ends = [safe_date(a.get("earlyEndDate")) for a in activities if safe_date(a.get("earlyEndDate"))]
    project_start = min(all_starts) if all_starts else None
    project_end = max(all_ends) if all_ends else None

    invalid_date_violators = []
    for a in schedulable:
        es = safe_date(a.get("earlyStartDate"))
        ef = safe_date(a.get("earlyEndDate"))
        reason = None
        if es is None and ef is None:
            reason = "Missing early start and finish dates"
        elif project_start and project_end:
            if es and (es < project_start or es > project_end):
                reason = f"Early start {es} outside project range"
            elif ef and ef > project_end:
                reason = f"Early finish {ef} outside project range"
        if reason:
            invalid_date_violators.append({
                "activityId": a["externalTaskId"],
                "activityName": a.get("taskName"),
                "reason": reason,
            })
    checkpoints.append(make_checkpoint(9, "Invalid Dates", invalid_date_violators, schedulable))

    # ── CP 10: Resources ──────────────────────────────────────────────────────
    resource_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "reason": "No resource assignment",
        }
        for a in schedulable
        if a["externalTaskId"] not in tasks_with_resources
    ]
    checkpoints.append(make_checkpoint(10, "Resources", resource_violators, schedulable))

    # ── CP 11: Missing Baseline ───────────────────────────────────────────────
    missing_bl_violators = [
        {
            "activityId": a["externalTaskId"],
            "activityName": a.get("taskName"),
            "reason": "Missing baseline start or finish date",
        }
        for a in schedulable
        if not a.get("targetStartDate") and not a.get("targetEndDate")
    ]
    checkpoints.append(make_checkpoint(11, "Missing Baseline", missing_bl_violators, schedulable))

    # ── CP 12: CPLI ───────────────────────────────────────────────────────────
    # Critical path activities: total float ≈ 0
    cp_activities = [
        a for a in schedulable
        if a.get("statusCode") != "TK_Complete"
        and safe_float(a.get("totalFloatHours")) is not None
        and safe_float(a.get("totalFloatHours")) <= 0
    ]
    cp_remaining_hrs = sum(safe_float(a.get("remainingDurationHours") or 0) or 0 for a in cp_activities)
    cp_float_hrs = sum(abs(safe_float(a.get("totalFloatHours") or 0) or 0) for a in cp_activities)

    if cp_remaining_hrs > 0:
        # CPLI = (CPL + TF) / CPL — TF for 0-float path is near 0, so CPLI ~ 1.0
        # Use average negative float to measure schedule pressure
        avg_neg_float = cp_float_hrs / len(cp_activities) if cp_activities else 0
        cpli_value = round(1.0 - (avg_neg_float / cp_remaining_hrs) if cp_remaining_hrs > 0 else 1.0, 3)
        cpli_value = max(0.0, cpli_value)
    else:
        cpli_value = 1.0

    cpli_status = "PASS" if cpli_value >= 1.0 else "FAIL"
    checkpoints.append({
        "id": 12,
        "name": "CPLI",
        "status": cpli_status,
        "score": round(cpli_value * 100, 2),
        "threshold": 100.0,
        "violatingCount": 0 if cpli_status == "PASS" else len(cp_activities),
        "totalCount": len(cp_activities),
        "cpliValue": cpli_value,
        "exceptions": (
            []
            if cpli_status == "PASS"
            else [{"reason": f"CPLI = {cpli_value} (< 1.0). Critical path has schedule pressure."}]
        ),
    })

    # ── CP 13: BEI ────────────────────────────────────────────────────────────
    if data_dt:
        planned_at_date = [
            a for a in schedulable
            if safe_date(a.get("earlyEndDate")) and safe_date(a.get("earlyEndDate")) <= data_dt
        ]
        actual_complete = [a for a in planned_at_date if a.get("statusCode") == "TK_Complete"]
        bei_value = (len(actual_complete) / len(planned_at_date)) if planned_at_date else 1.0
        bei_value = round(bei_value, 3)
        bei_status = "PASS" if bei_value >= 0.95 else "FAIL"
        behind = len(planned_at_date) - len(actual_complete)
        checkpoints.append({
            "id": 13,
            "name": "BEI",
            "status": bei_status,
            "score": round(bei_value * 100, 2),
            "threshold": 95.0,
            "violatingCount": behind,
            "totalCount": len(planned_at_date),
            "beiValue": bei_value,
            "exceptions": (
                []
                if bei_status == "PASS"
                else [{"reason": f"BEI = {bei_value} (< 0.95). {behind} activities planned to be complete by data date are not finished."}]
            ),
        })
    else:
        checkpoints.append({
            "id": 13,
            "name": "BEI",
            "status": "PASS",
            "score": 100.0,
            "threshold": 95.0,
            "violatingCount": 0,
            "totalCount": 0,
            "beiValue": 1.0,
            "exceptions": [],
            "note": "No data date available — BEI not evaluated.",
        })

    # ── CP 14: Critical Path Test ─────────────────────────────────────────────
    # Verify a connected critical path (zero-float chain) exists across the schedule
    zero_float_ids = {
        a["externalTaskId"]
        for a in schedulable
        if safe_float(a.get("totalFloatHours")) is not None and abs(safe_float(a.get("totalFloatHours"))) < 0.5
    }

    # Check connectivity: at least one zero-float activity must have a predecessor that is also zero-float
    zero_float_connected = any(
        r.get("externalPredecessorTaskId") in zero_float_ids
        for r in task_predecessors
        if r.get("externalTaskId") in zero_float_ids
    )

    cp_test_pass = bool(zero_float_ids) and zero_float_connected
    checkpoints.append({
        "id": 14,
        "name": "Critical Path Test",
        "status": "PASS" if cp_test_pass else "FAIL",
        "score": 100.0 if cp_test_pass else 0.0,
        "threshold": 100.0,
        "violatingCount": 0 if cp_test_pass else len(schedulable),
        "totalCount": len(schedulable),
        "exceptions": (
            []
            if cp_test_pass
            else [{"reason": (
                "No connected critical path found. " + (
                    f"{len(zero_float_ids)} zero-float activities exist but none are linked to each other."
                    if zero_float_ids
                    else "No activities with zero total float."
                )
            )}]
        ),
    })

    pass_count = sum(1 for cp in checkpoints if cp["status"] == "PASS")
    fail_count = len(checkpoints) - pass_count

    return {
        "checkpoints": checkpoints,
        "overallStatus": "PASS" if fail_count == 0 else "FAIL",
        "passCount": pass_count,
        "failCount": fail_count,
        "schedulableActivityCount": len(schedulable),
        "relationshipCount": len(task_predecessors),
    }

# ─────────────────────────────────────────────────────────────────────────────


def build_payload(file_path: Path):
    file_metadata = parse_filename_metadata(file_path)
    raw_tables, metadata = parse_xer_tables(file_path)

    resources_by_id = {
        row.get("rsrc_id"): row
        for row in raw_tables["RSRC"]
        if row.get("rsrc_id")
    }
    enriched_metadata = enrich_metadata(metadata, raw_tables["PROJECT"], file_metadata)

    activities = map_activities(raw_tables["TASK"])
    task_predecessors = map_task_predecessors(raw_tables["TASKPRED"])
    task_resources = map_task_resources(raw_tables["TASKRSRC"], resources_by_id)

    payload = {
        "projectCode": enriched_metadata["projectCode"],
        "dataDate": enriched_metadata["dataDate"],
        "revisionLabel": enriched_metadata["revisionLabel"],
        "headerInfo": enriched_metadata["headerInfo"],
        "activities": activities,
        "taskResources": task_resources,
        "resources": map_resources(raw_tables["RSRC"]),
        "calendars": map_calendars(raw_tables["CALENDAR"]),
        "activityCodes": map_activity_codes(raw_tables["ACTVCODE"]),
        "taskActivityCodes": map_task_activity_codes(raw_tables["TASKACTV"]),
        "taskPredecessors": task_predecessors,
    }

    payload["summary"] = {
        "activities": len(payload["activities"]),
        "taskResources": len(payload["taskResources"]),
        "resources": len(payload["resources"]),
        "calendars": len(payload["calendars"]),
        "activityCodes": len(payload["activityCodes"]),
        "taskActivityCodes": len(payload["taskActivityCodes"]),
        "taskPredecessors": len(payload["taskPredecessors"]),
        "detectedTables": {
            table_name: len(rows)
            for table_name, rows in raw_tables.items()
        },
    }

    payload["dcmaCheckpoints"] = compute_dcma_checkpoints(
        activities,
        task_predecessors,
        task_resources,
        enriched_metadata["dataDate"],
    )

    return payload


def main():
    args = parse_args()
    file_path = Path(args.file).expanduser().resolve()
    output_path = Path(args.output).expanduser().resolve()

    payload = build_payload(file_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps({"status": "ok", "output": str(output_path), "summary": payload["summary"]}))


if __name__ == "__main__":
    main()
