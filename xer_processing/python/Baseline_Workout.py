#!/usr/bin/env python
# coding: utf-8

# ## Baseline_Workout
# 
# New notebook

# In[26]:


# %pip install xer_reader


# In[27]:


projname = ["RCNSTTI_20250606_Rev.03.xer"]


# In[28]:


import os
from xer_reader import XerReader
import pandas as pd
from io import StringIO
from pyspark.sql import SparkSession
from pyspark.sql.functions import lit, to_date
from pyspark.sql.types import StringType, DateType

# ---------------------------------------------------------------------------
# Spark session
# ---------------------------------------------------------------------------
if 'spark' not in locals():
    spark = SparkSession.builder.appName("XERReader").getOrCreate()

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
xer_directory = "/lakehouse/default/Files/"

tables = {
    "TASK":     "TASK",
    "TASKRSRC": "TASKRSRC",
    "RSRC":     "RSRC",
    "CALENDAR": "CALENDAR",
    "ACTVCODE": "ACTVCODE",
    "TASKACTV": "TASKACTV",
    "TASKPRED": "TASKPRED",
}

# ---------------------------------------------------------------------------
# Read each XER file and collect per-table DataFrames
# ---------------------------------------------------------------------------
collected = {tbl: [] for tbl in tables}

for filename in projname:
    if not filename.endswith(".xer"):
        continue

    file_path = os.path.join(xer_directory, filename)
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        continue

    # Expect PROJECTCODE_YYYYMMDD_Rev.xer
    parts = filename[:-4].split('_')
    if len(parts) < 3:
        print(f"Skipping {filename}: unexpected name format")
        continue

    projcode = parts[0]
    date_str = parts[1]
    rev      = parts[2].replace("Rev.", "")

    try:
        reader = XerReader(file_path)
    except Exception as e:
        print(f"Error opening {filename}: {e}")
        continue

    for xer_table in tables:
        try:
            raw = reader.get_table_str(xer_table)
            pdf = pd.read_csv(StringIO(raw), sep="\t")
            sdf = spark.createDataFrame(pdf)

            sdf = (
                sdf
                .withColumn("projcode", lit(projcode).cast(StringType()))
                .withColumn("date",     to_date(lit(date_str), "yyyyMMdd").cast(DateType()))
                .withColumn("rev",      lit(rev).cast(StringType()))
            )

            collected[xer_table].append(sdf)

        except Exception as e:
            print(f"{xer_table} error in {filename}: {e}")

# ---------------------------------------------------------------------------
# Helper: align a DataFrame to an existing Delta table's schema
# ---------------------------------------------------------------------------
def align_to_target(df, target_table):
    """Cast every column in df to match target Delta table's types and column order.
    Adds null columns for missing fields and keeps brand-new columns at the end."""
    target_fields = {f.name: f.dataType for f in spark.table(target_table).schema.fields}
    target_order  = spark.table(target_table).columns

    aligned_cols = []
    for c in target_order:
        if c in df.columns:
            aligned_cols.append(df[c].cast(target_fields[c]).alias(c))
        else:
            aligned_cols.append(lit(None).cast(target_fields[c]).alias(c))

    new_cols = [c for c in df.columns if c not in target_fields]
    if new_cols:
        print(f"[{target_table}] new columns will be added via mergeSchema: {new_cols}")
        for c in new_cols:
            aligned_cols.append(df[c])

    return df.select(*aligned_cols)

# ---------------------------------------------------------------------------
# Union per-table batches and write to Delta
# ---------------------------------------------------------------------------
final_dfs = {}

for xer_table, delta_name in tables.items():
    dfs = collected[xer_table]
    if not dfs:
        print(f"No data for {xer_table}, skipping write.")
        continue

    # Union all batches
    combined = dfs[0]
    for df in dfs[1:]:
        combined = combined.unionByName(df, allowMissingColumns=True)

    # Expose for later cells
    final_dfs[delta_name] = combined
    globals()[f"df_{delta_name}"] = combined

    # Align to existing schema if the table exists
    if spark.catalog.tableExists(delta_name):
        to_write = align_to_target(combined, delta_name)
    else:
        print(f"[{delta_name}] table does not exist yet, creating from inferred schema.")
        to_write = combined

    # Write
    (to_write.write
        .mode("append")
        .format("delta")
        .option("mergeSchema", "true")
        .option("delta.columnMapping.mode", "name")
        .option("delta.minReaderVersion", "2")
        .option("delta.minWriterVersion", "5")
        .saveAsTable(delta_name))

    print(f"Wrote Delta table '{delta_name}' and created variable df_{delta_name}")


# In[29]:


# df_TASK
# df_TASKRSRC
# df_RSRC
# df_CALENDAR
# df_ACTVCODE
# df_TASKACTV
# df_TASKPRED


# In[30]:


from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, StructType, StructField, IntegerType, StringType
import re

# ---------------------------------------------------------
# helper parser for one calendar string
# ---------------------------------------------------------
def parse_calendar_days(clndr_data):
    if clndr_data is None:
        return []

    s = clndr_data

    # normalize the weird delimiter chars / spaces a bit
    s = s.replace("\x7f", "")
    s = re.sub(r"\s+", "", s)

    # isolate DaysOfWeek block only
    m = re.search(r"\(0\|\|DaysOfWeek\(\)\((.*)\)\)\(0\|\|(?:VIEW|Exceptions)\(", s)
    if not m:
        m = re.search(r"\(0\|\|DaysOfWeek\(\)\((.*)\)\)\)?$", s)

    if not m:
        return []

    days_block = m.group(1)

    results = []
    i = 0
    n = len(days_block)

    while i < n:
        # find day start like (0||1()( or (0||7()(
        mday = re.search(r"\(0\|\|(\d+)\(\)\(", days_block[i:])
        if not mday:
            break

        day = int(mday.group(1))
        start = i + mday.start()
        j = i + mday.end()

        # walk parentheses to capture this full day block
        depth = 1
        while j < n and depth > 0:
            if days_block[j] == "(":
                depth += 1
            elif days_block[j] == ")":
                depth -= 1
            j += 1

        block = days_block[start:j]

        # extract shifts inside the day block
        shifts = re.findall(r"\(0\|\|(\d+)\(s\|([\d:]+)\|f\|([\d:]+)\)\(\)\)", block)

        row = {
            "day_of_week": day,
            "shift0_start": None, "shift0_finish": None,
            "shift1_start": None, "shift1_finish": None,
            "shift2_start": None, "shift2_finish": None
        }

        for idx, (_, st, fn) in enumerate(shifts[:3]):
            row[f"shift{idx}_start"] = st
            row[f"shift{idx}_finish"] = fn

        results.append(row)
        i = j

    return results


# ---------------------------------------------------------
# UDF schema
# ---------------------------------------------------------
schema = ArrayType(
    StructType([
        StructField("day_of_week", IntegerType(), True),
        StructField("shift0_start", StringType(), True),
        StructField("shift0_finish", StringType(), True),
        StructField("shift1_start", StringType(), True),
        StructField("shift1_finish", StringType(), True),
        StructField("shift2_start", StringType(), True),
        StructField("shift2_finish", StringType(), True),
    ])
)

parse_calendar_days_udf = F.udf(parse_calendar_days, schema)

# ---------------------------------------------------------
# parse directly from clndr_data
# ---------------------------------------------------------
result = (
    df_CALENDAR
    .select(
        "projcode",
        "clndr_id",
        F.explode(parse_calendar_days_udf(F.col("clndr_data"))).alias("d")
    )
    .select(
        "projcode",
        "clndr_id",
        F.col("d.day_of_week").alias("day_of_week"),
        F.col("d.shift0_start"),
        F.col("d.shift0_finish"),
        F.col("d.shift1_start"),
        F.col("d.shift1_finish"),
        F.col("d.shift2_start"),
        F.col("d.shift2_finish"),
    )
)


# In[31]:


from pyspark.sql.functions import (
    col, to_timestamp, array, array_min, array_max,
    date_format, coalesce, unix_timestamp, lit, expr
)

# assume `result` is your DataFrame with shift0_start/finish … shift2_start/finish

df2 = (
    result
      # parse each shift time into a timestamp (no date portion; just time)
      .withColumn(
         "starts",
         array(
           to_timestamp(col("shift0_start"), "HH:mm"),
           to_timestamp(col("shift1_start"), "HH:mm"),
           to_timestamp(col("shift2_start"), "HH:mm")
         )
      )
      .withColumn(
         "finishes",
         array(
           to_timestamp(col("shift0_finish"), "HH:mm"),
           to_timestamp(col("shift1_finish"), "HH:mm"),
           to_timestamp(col("shift2_finish"), "HH:mm")
         )
      )
      # earliest and latest
      .withColumn("min_start_ts", array_min(col("starts")))
      .withColumn("max_finish_ts", array_max(col("finishes")))
      .withColumn("min_start",  date_format(col("min_start_ts"),  "HH:mm"))
      .withColumn("max_finish", date_format(col("max_finish_ts"), "HH:mm"))
      # compute total seconds per shift, defaulting nulls to 0
      .withColumn(
        "total_seconds",
        coalesce(
          unix_timestamp(col("shift0_finish"), "HH:mm")
          - unix_timestamp(col("shift0_start"), "HH:mm"),
          lit(0)
        )
        + coalesce(
          unix_timestamp(col("shift1_finish"), "HH:mm")
          - unix_timestamp(col("shift1_start"), "HH:mm"),
          lit(0)
        )
        + coalesce(
          unix_timestamp(col("shift2_finish"), "HH:mm")
          - unix_timestamp(col("shift2_start"), "HH:mm"),
          lit(0)
        )
      )
      # convert to hours (round to 2 decimal places)
      .withColumn("total_hours_worked", expr("round(total_seconds/3600, 2)"))
      # drop intermediate helper cols
      .drop("starts", "finishes", "min_start_ts", "max_finish_ts", "total_seconds")
)

# df2.select(
#     "projcode",
#     "clndr_id",
#     "day_of_week",
#     "min_start",
#     "max_finish",
#     "total_hours_worked"
# ).show(truncate=False)


# In[32]:


df2.write \
    .mode("append") \
    .option("overwriteSchema", "true") \
    .format("delta") \
    .saveAsTable("calendar_workdays")




# In[33]:


from pyspark.sql import SparkSession, Window
from pyspark.sql.functions import (
    split, posexplode, col, regexp_replace, trim,
    regexp_extract, when, expr, last, sum as _sum,
    min as _min, max as _max, unix_timestamp,
    lit, concat_ws, collect_list, format_string,
    coalesce
)
import re

spark = SparkSession.builder.appName("CalendarExceptions").getOrCreate()

# ---------------------------------------------------------
# 1) Read raw calendar table
# ---------------------------------------------------------
df = df_CALENDAR

# ---------------------------------------------------------
# 2) Constants
# ---------------------------------------------------------
DELIM = "\x7f\x7f    "
PREFIX_EXC = re.escape("(0||Exceptions()(")
SUFFIX_EXC = r"\)\)\).*"

# ---------------------------------------------------------
# 3) Split on ShowTotal, preserve order
# ---------------------------------------------------------
exc_pieces = (
    df
    .select(
        "clndr_id", "projcode",
        posexplode(split(col("clndr_data"), "ShowTotal")).alias("piece_pos", "piece")
    )
    .filter(col("piece").contains("Exceptions"))
)

# ---------------------------------------------------------
# 4) Split exception block into lines, preserve order
# ---------------------------------------------------------
lines = (
    exc_pieces
    .select(
        "clndr_id", "projcode", "piece_pos",
        posexplode(split(col("piece"), DELIM)).alias("line_pos", "line")
    )
)

# ---------------------------------------------------------
# 5) Clean lines
# ---------------------------------------------------------
cleaned = (
    lines
    .withColumn(
        "line",
        trim(
            regexp_replace(
                regexp_replace(col("line"), "^" + PREFIX_EXC, ""),
                SUFFIX_EXC,
                ""
            )
        )
    )
    .filter((col("line").isNotNull()) & (col("line") != ""))
)

# ---------------------------------------------------------
# 6) Extract date / start / finish
# ---------------------------------------------------------
extracted = (
    cleaned
    .withColumn("ex_serial", regexp_extract(col("line"), r"d\|(\d+)", 1).cast("int"))
    .withColumn("exception_start", regexp_extract(col("line"), r"s\|([\d:]+)", 1))
    .withColumn("exception_finish", regexp_extract(col("line"), r"f\|([\d:]+)", 1))
    .withColumn(
        "exception_start",
        when(col("exception_start") == "", None).otherwise(col("exception_start"))
    )
    .withColumn(
        "exception_finish",
        when(col("exception_finish") == "", None).otherwise(col("exception_finish"))
    )
    .withColumn(
        "ex_date",
        when(col("ex_serial").isNotNull(),
             expr("date_add(date('1899-12-30'), ex_serial)"))
    )
)

# ---------------------------------------------------------
# 7) Forward-fill date in true source order
# ---------------------------------------------------------
w_order = (
    Window.partitionBy("clndr_id", "projcode")
    .orderBy("piece_pos", "line_pos")
    .rowsBetween(Window.unboundedPreceding, 0)
)

exc = extracted.withColumn(
    "filled_date",
    last("ex_date", ignorenulls=True).over(w_order)
)

# ---------------------------------------------------------
# 8) Build shift sequence inside each filled_date
# ---------------------------------------------------------
w_shift = (
    Window.partitionBy("clndr_id", "projcode", "filled_date")
    .orderBy("piece_pos", "line_pos")
    .rowsBetween(Window.unboundedPreceding, 0)
)

exc = (
    exc
    .withColumn(
        "new_shift_flag",
        when(col("exception_start").isNotNull(), 1).otherwise(0)
    )
    .withColumn("shift_seq", _sum("new_shift_flag").over(w_shift))
)

# ---------------------------------------------------------
# 9) Keep all exception dates
#    This preserves holiday-only dates
# ---------------------------------------------------------
all_exception_dates = (
    exc
    .filter(col("filled_date").isNotNull())
    .select(
        "clndr_id",
        "projcode",
        col("filled_date").alias("exception_date")
    )
    .dropDuplicates(["clndr_id", "projcode", "exception_date"])
)

# ---------------------------------------------------------
# 10) Keep only shift rows for time-based exceptions
# ---------------------------------------------------------
shift_rows = (
    exc
    .filter(col("filled_date").isNotNull())
    .filter(
        col("exception_start").isNotNull() |
        col("exception_finish").isNotNull()
    )
    .filter(col("shift_seq") > 0)
)

# ---------------------------------------------------------
# 11) Collapse each shift separately
# ---------------------------------------------------------
shift_summary = (
    shift_rows
    .groupBy("clndr_id", "projcode", "filled_date", "shift_seq")
    .agg(
        _min("exception_start").alias("shift_start"),
        _max("exception_finish").alias("shift_finish")
    )
    .withColumnRenamed("filled_date", "exception_date")
)

# ---------------------------------------------------------
# 12) Calculate hours per shift
# ---------------------------------------------------------
shift_summary = (
    shift_summary
    .withColumn(
        "shift_hours",
        when(
            col("shift_start").isNull() | col("shift_finish").isNull(),
            lit(0.0)
        ).otherwise(
            when(
                unix_timestamp("shift_finish", "H:mm") >= unix_timestamp("shift_start", "H:mm"),
                (unix_timestamp("shift_finish", "H:mm") - unix_timestamp("shift_start", "H:mm")) / 3600.0
            ).otherwise(
                ((unix_timestamp("shift_finish", "H:mm") + lit(86400)) - unix_timestamp("shift_start", "H:mm")) / 3600.0
            )
        )
    )
)

# ---------------------------------------------------------
# 13) Roll up time-based exception shifts
# ---------------------------------------------------------
daily_shift_summary = (
    shift_summary
    .groupBy("clndr_id", "projcode", "exception_date")
    .agg(
        _sum("shift_hours").alias("ex_total_shift_hours"),
        concat_ws(
            " | ",
            collect_list(
                format_string("%s-%s", col("shift_start"), col("shift_finish"))
            )
        ).alias("ex_shift_pattern")
    )
)

# ---------------------------------------------------------
# 14) Merge all exception dates with shift totals
#     Holidays get 0 hours
# ---------------------------------------------------------
daily_summary = (
    all_exception_dates
    .join(
        daily_shift_summary,
        on=["clndr_id", "projcode", "exception_date"],
        how="left"
    )
    .withColumn("ex_total_shift_hours", coalesce(col("ex_total_shift_hours"), lit(0.0)))
    .withColumn("ex_shift_pattern", coalesce(col("ex_shift_pattern"), lit("HOLIDAY")))
)

# ---------------------------------------------------------
# 15) Read calendar meta
# ---------------------------------------------------------
cal_df = (
    spark.read
         .table("calendar")
         .select(
             col("clndr_id").cast("bigint"),
             col("clndr_name"),
             col("proj_id"),
             col("projcode"),
             col("date"),
             col("rev")
         )
         .dropDuplicates(["clndr_id", "projcode"])
)

# ---------------------------------------------------------
# 16) Final output
# ---------------------------------------------------------
final_df = (
    daily_summary
    .join(cal_df, on=["clndr_id", "projcode"], how="left")
    .select(
        "clndr_id",
        "projcode",
        "clndr_name",
        "date",
        "rev",
        "exception_date",
        "ex_shift_pattern",
        "ex_total_shift_hours"
    )
)

final_df.write.mode("append") \
    .option("overwriteSchema", "true") \
    .format("delta") \
    .saveAsTable("calendar_exceptions")


# rsrc columns : 
# 
# Columns_selected : ['rsrc_id', 'rsrc_name', 'rsrc_short_name', 'rsrc_type','projcode', 'date', 'rev']
# 
# ['rsrc_id', 'parent_rsrc_id', 'clndr_id', 'role_id', 'shift_id', 'user_id', 'pobs_id', 'guid', 'rsrc_seq_num', 'email_addr', 'employee_code', 'office_phone', 'other_phone', 'rsrc_name', 'rsrc_short_name', 'rsrc_title_name', 'def_qty_per_hr', 'cost_qty_type', 'ot_factor', 'active_flag', 'auto_compute_act_flag', 'def_cost_qty_link_flag', 'ot_flag', 'curr_id', 'unit_id', 'rsrc_type', 'location_id', 'rsrc_notes', 'load_tasks_flag', 'level_flag', 'last_checksum', 'projcode', 'date', 'rev']

# task_columns
# 
# Columns Selected = ['task_id', 'proj_id', 'wbs_id', 'clndr_id', 'phys_complete_pct', 'complete_pct_type', 'task_type', 'duration_type', 'status_code', 'task_code', 'task_name', 'rsrc_id', 'total_float_hr_cnt', 'free_float_hr_cnt', 'remain_drtn_hr_cnt', 'act_work_qty', 'remain_work_qty', 'target_work_qty', 'target_drtn_hr_cnt', 'target_equip_qty', 'act_equip_qty', 'remain_equip_qty', 'late_start_date', 'late_end_date', 'expect_end_date', 'early_start_date', 'early_end_date', 'restart_date', 'reend_date', 'target_start_date', 'target_end_date', 'rem_late_start_date', 'rem_late_end_date', 'float_path', 'float_path_order','projcode', 'date', 'rev']
# 
# ['task_id', 'proj_id', 'wbs_id', 'clndr_id', 'phys_complete_pct', 'rev_fdbk_flag', 'est_wt', 'lock_plan_flag', 'auto_compute_act_flag', 'complete_pct_type', 'task_type', 'duration_type', 'status_code', 'task_code', 'task_name', 'rsrc_id', 'total_float_hr_cnt', 'free_float_hr_cnt', 'remain_drtn_hr_cnt', 'act_work_qty', 'remain_work_qty', 'target_work_qty', 'target_drtn_hr_cnt', 'target_equip_qty', 'act_equip_qty', 'remain_equip_qty', 'cstr_date', 'act_start_date', 'act_end_date', 'late_start_date', 'late_end_date', 'expect_end_date', 'early_start_date', 'early_end_date', 'restart_date', 'reend_date', 'target_start_date', 'target_end_date', 'rem_late_start_date', 'rem_late_end_date', 'cstr_type', 'priority_type', 'suspend_date', 'resume_date', 'float_path', 'float_path_order', 'guid', 'tmpl_guid', 'cstr_date2', 'cstr_type2', 'driving_path_flag', 'act_this_per_work_qty', 'act_this_per_equip_qty', 'external_early_start_date', 'external_late_end_date', 'create_date', 'update_date', 'create_user', 'update_user', 'location_id', 'projcode', 'date', 'rev']
# 

# taskrsrc_columns 
# 
# Column selected = [ 'task_id', 'proj_id',  'rsrc_id', 'remain_qty', 'target_qty',  'act_reg_qty',  'cost_per_qty', 'target_cost', 'act_reg_cost',  'remain_cost', 'act_start_date', 'act_end_date', 'restart_date', 'reend_date', 'target_start_date', 'target_end_date', 'rem_late_start_date', 'rem_late_end_date','rsrc_type', 'projcode', 'date', 'rev']
# 
# ['taskrsrc_id', 'task_id', 'proj_id', 'cost_qty_link_flag', 'role_id', 'acct_id', 'rsrc_id', 'pobs_id', 'skill_level', 'remain_qty', 'target_qty', 'remain_qty_per_hr', 'target_lag_drtn_hr_cnt', 'target_qty_per_hr', 'act_ot_qty', 'act_reg_qty', 'relag_drtn_hr_cnt', 'ot_factor', 'cost_per_qty', 'target_cost', 'act_reg_cost', 'act_ot_cost', 'remain_cost', 'act_start_date', 'act_end_date', 'restart_date', 'reend_date', 'target_start_date', 'target_end_date', 'rem_late_start_date', 'rem_late_end_date', 'rollup_dates_flag', 'target_crv', 'remain_crv', 'actual_crv', 'ts_pend_act_end_flag', 'guid', 'rate_type', 'act_this_per_cost', 'act_this_per_qty', 'curv_id', 'rsrc_type', 'cost_per_qty_source_type', 'create_user', 'create_date', 'has_rsrchours', 'taskrsrc_sum_id', 'projcode', 'date', 'rev']
# 

# 

# In[34]:


from pyspark.sql.functions import col
from pyspark.sql.types import DoubleType, TimestampType, StringType, IntegerType

# ---------- 1. Select required columns ----------
task_df = df_TASK.select(
    "task_id", "wbs_id", "clndr_id", "phys_complete_pct", "complete_pct_type",
    "task_type", "duration_type", "status_code", "task_code", "task_name",
    "projcode", "date", "rev"
)

taskrsrc_df = df_TASKRSRC.select(
    "task_id", "proj_id", "rsrc_id",
    "remain_qty", "target_qty", "act_reg_qty",
    "cost_per_qty", "target_cost", "act_reg_cost", "remain_cost",
    "act_start_date", "act_end_date", "restart_date", "reend_date",
    "target_start_date", "target_end_date",
    "rem_late_start_date", "rem_late_end_date",
    "rsrc_type", "projcode", "date", "rev"
)

rsrc_df = df_RSRC.select(
    "rsrc_id", "rsrc_name", "rsrc_short_name",
    "projcode", "date", "rev"
)

# ---------- 2. Enforce consistent data types (prevents Delta merge errors) ----------
numeric_cols = [
    "remain_qty", "target_qty", "act_reg_qty",
    "cost_per_qty", "target_cost", "act_reg_cost", "remain_cost"
]
date_cols = [
    "act_start_date", "act_end_date", "restart_date", "reend_date",
    "target_start_date", "target_end_date",
    "rem_late_start_date", "rem_late_end_date"
]
string_cols = [
    "task_id", "proj_id", "rsrc_id", "rsrc_type",
    "projcode", "rev"
]

for c in numeric_cols:
    taskrsrc_df = taskrsrc_df.withColumn(c, col(c).cast(DoubleType()))

for c in date_cols:
    taskrsrc_df = taskrsrc_df.withColumn(c, col(c).cast(TimestampType()))

for c in string_cols:
    taskrsrc_df = taskrsrc_df.withColumn(c, col(c).cast(StringType()))

taskrsrc_df = taskrsrc_df.withColumn("date", col("date").cast(TimestampType()))

# Align task_df and rsrc_df key types so joins don't fail
for c in ["task_id", "wbs_id", "clndr_id", "status_code", "task_code",
          "task_name", "task_type", "duration_type", "complete_pct_type",
          "projcode", "rev"]:
    task_df = task_df.withColumn(c, col(c).cast(StringType()))
task_df = task_df.withColumn("phys_complete_pct", col("phys_complete_pct").cast(DoubleType()))
task_df = task_df.withColumn("date", col("date").cast(TimestampType()))

for c in ["rsrc_id", "rsrc_name", "rsrc_short_name", "projcode", "rev"]:
    rsrc_df = rsrc_df.withColumn(c, col(c).cast(StringType()))
rsrc_df = rsrc_df.withColumn("date", col("date").cast(TimestampType()))

print("RSRC columns:", rsrc_df.columns)

# ---------- 3. Join TASKRSRC with RSRC ----------
taskrsrc_df = taskrsrc_df.alias("TaskRsrc").join(
    rsrc_df.alias("Rsrc"),
    (col("TaskRsrc.projcode") == col("Rsrc.projcode")) &
    (col("TaskRsrc.rev")      == col("Rsrc.rev"))      &
    (col("TaskRsrc.date")     == col("Rsrc.date"))     &
    (col("TaskRsrc.rsrc_id")  == col("Rsrc.rsrc_id")),
    how="left"
).drop(
    col("Rsrc.projcode"),
    col("Rsrc.rev"),
    col("Rsrc.date"),
    col("Rsrc.rsrc_id")
)

# ---------- 4. Join TASKRSRC with TASK ----------
taskrsrc_df = taskrsrc_df.alias("TaskRsrc").join(
    task_df.alias("b"),
    (col("TaskRsrc.projcode") == col("b.projcode")) &
    (col("TaskRsrc.rev")      == col("b.rev"))      &
    (col("TaskRsrc.date")     == col("b.date"))     &
    (col("TaskRsrc.task_id")  == col("b.task_id")),
    how="left"
).drop(
    col("b.projcode"),
    col("b.rev"),
    col("b.date"),
    col("b.task_id")
)

# ---------- 5. Align to existing Delta table schema (final safety net) ----------
if spark.catalog.tableExists("TASKRSRC"):
    target_schema = {f.name: f.dataType for f in spark.table("TASKRSRC").schema.fields}
    for c in taskrsrc_df.columns:
        if c in target_schema and taskrsrc_df.schema[c].dataType != target_schema[c]:
            taskrsrc_df = taskrsrc_df.withColumn(c, col(c).cast(target_schema[c]))

# ---------- 6. Write to Delta table ----------
taskrsrc_df.write.mode("append") \
    .option("mergeSchema", "true") \
    .option("delta.columnMapping.mode", "name") \
    .option("delta.minReaderVersion", "2") \
    .option("delta.minWriterVersion", "5") \
    .format("delta") \
    .saveAsTable("TASKRSRC")

# print("Final columns:", taskrsrc_df.columns)


# In[35]:


from pyspark.sql import functions as F
from pyspark.sql.window import Window

# =========================================================
# 1) Base data
# =========================================================
cal = df2

# exception table
exp_df = final_df.select(
    "projcode",
    "rev",
    "clndr_id",
    F.col("exception_date").alias("Day"),
    "ex_total_shift_hours"
)

# bring early dates from TASK
task_early = df_TASK.select(
    "projcode",
    "rev",
    "task_id",
    "clndr_id",
    "task_code",
    "early_start_date",
    "early_end_date","target_drtn_hr_cnt","remain_drtn_hr_cnt"
).dropDuplicates(["projcode", "rev", "task_id"])

# TASKRSRC base
base = (
    taskrsrc_df.alias("a")
    .join(
        task_early.alias("t"),
        on=["projcode", "rev", "task_id"],
        how="left"
    )
    .select(
        F.col("a.projcode"),
        F.col("a.rev"),
        F.col("a.task_id"),
        F.coalesce(F.col("a.task_code"), F.col("t.task_code")).alias("task_code"),
        F.coalesce(F.col("a.clndr_id"), F.col("t.clndr_id")).alias("clndr_id"),
        F.col("a.rsrc_id"),
        F.col("a.rsrc_name"),
        F.col("a.rsrc_short_name"),
        F.col("a.status_code"),
        F.col("a.complete_pct_type"),
        F.col("a.phys_complete_pct"),
        F.col("a.target_qty"),
        F.col("a.remain_qty"),
        F.col("a.target_cost"),
        F.col("a.remain_cost"),
        F.col("a.act_reg_cost"),
        F.col("a.cost_per_qty"),
        F.col("a.act_start_date"),
        F.col("a.act_end_date"),
        F.col("a.restart_date"),
        F.col("a.reend_date"),
        F.col("a.target_start_date"),
        F.col("a.target_end_date"),
        F.col("a.date").alias("file_data_date"),
        F.col("t.early_start_date"),
        F.col("t.early_end_date"),
        F.col("t.target_drtn_hr_cnt"),
        F.col("t.remain_drtn_hr_cnt")
    )
)

# =========================================================
# 2) % complete logic
# =========================================================
# phys_complete_pct may be 0-100 or 0-1, so normalize
base = base.withColumn(
    "phys_pct_norm",
    F.when(F.col("phys_complete_pct").isNull(), F.lit(None).cast("double"))
     .when(F.col("phys_complete_pct") > 1, F.col("phys_complete_pct") / 100.0)
     .otherwise(F.col("phys_complete_pct").cast("double"))
)

base = base.withColumn(
    "qty_pct_calc",
    F.when(
        (F.col("target_drtn_hr_cnt").isNotNull()) & (F.col("target_drtn_hr_cnt") != 0),
        (
            F.col("target_drtn_hr_cnt") - F.coalesce(F.col("remain_drtn_hr_cnt"), F.lit(0.0))
        ) / F.col("target_drtn_hr_cnt")
    ).otherwise(F.lit(0.0))
)

base = base.withColumn(
    "pct_comp",
    F.when(F.col("complete_pct_type") == "CP_Phys", F.col("phys_pct_norm"))
     .otherwise(F.col("qty_pct_calc"))
)

base = base.withColumn(
    "pct_comp",
    F.when(F.col("pct_comp").isNull(), F.lit(0.0))
     .when(F.col("pct_comp") < 0, F.lit(0.0))
     .when(F.col("pct_comp") > 1, F.lit(1.0))
     .otherwise(F.col("pct_comp"))
)

# =========================================================
# 3) Actual / Remaining quantities and costs
# =========================================================
base = (
    base
    .withColumn("actual_units", F.coalesce(F.col("target_qty"), F.lit(0.0)) * F.col("pct_comp"))
    .withColumn("remain_units_calc", F.coalesce(F.col("target_qty"), F.lit(0.0)) - F.col("actual_units"))
)

# clamp remain units
base = base.withColumn(
    "remain_units",
    F.when(F.col("remain_units_calc") < 0, F.lit(0.0))
     .otherwise(F.col("remain_units_calc"))
).drop("remain_units_calc")

# cost split:
# use cost_per_qty where possible, else proportional target_cost
base = (
    base
    .withColumn(
        "actual_cost_calc",
        F.when(F.col("cost_per_qty").isNotNull(), F.col("actual_units") * F.col("cost_per_qty"))
         .when(
             (F.col("target_qty").isNotNull()) & (F.col("target_qty") != 0),
             F.col("target_cost") * (F.col("actual_units") / F.col("target_qty"))
         )
         .otherwise(F.lit(0.0))
    )
    .withColumn(
        "remain_cost_calc",
        F.when(F.col("cost_per_qty").isNotNull(), F.col("remain_units") * F.col("cost_per_qty"))
         .when(
             (F.col("target_qty").isNotNull()) & (F.col("target_qty") != 0),
             F.col("target_cost") * (F.col("remain_units") / F.col("target_qty"))
         )
         .otherwise(F.lit(0.0))
    )
)

# =========================================================
# 4) Parse timestamps / dates
# =========================================================
base = (
    base
    .withColumn("early_start_ts", F.to_timestamp("early_start_date"))
    .withColumn("early_end_ts", F.to_timestamp("early_end_date"))
    .withColumn("act_start_ts", F.to_timestamp("act_start_date"))
    .withColumn("act_end_ts", F.to_timestamp("act_end_date"))
    .withColumn("restart_ts", F.to_timestamp("restart_date"))
    .withColumn("reend_ts", F.to_timestamp("reend_date"))
    .withColumn("file_data_ts", F.to_timestamp("file_data_date"))
)

# =========================================================
# 5) Create bands
# =========================================================

# ---------- NOT STARTED -> REMAINING on EARLY dates ----------
not_started_rem = (
    base
    .filter(F.col("status_code") == "TK_NotStart")
    .withColumn("dist_type", F.lit("remaining"))
    .withColumn("band_source", F.lit("not_started_early"))
    .withColumn("band_start_ts", F.col("early_start_ts"))
    .withColumn("band_end_ts", F.col("early_end_ts"))
    .withColumn("band_units", F.col("remain_units"))
    .withColumn("band_cost", F.col("remain_cost_calc"))
)

# ---------- COMPLETE -> ACTUAL on ACTUAL dates ----------
complete_act = (
    base
    .filter(F.col("status_code") == "TK_Complete")
    .withColumn("dist_type", F.lit("actual"))
    .withColumn("band_source", F.lit("complete_actual"))
    .withColumn("band_start_ts", F.col("act_start_ts"))
    .withColumn("band_end_ts", F.col("act_end_ts"))
    .withColumn("band_units", F.col("actual_units"))
    .withColumn("band_cost", F.col("actual_cost_calc"))
)

# ---------- ACTIVE -> ACTUAL part ----------
active_act = (
    base
    .filter(F.col("status_code") == "TK_Active")
    .withColumn("dist_type", F.lit("actual"))
    .withColumn("band_source", F.lit("active_actual"))
    .withColumn("band_start_ts", F.col("act_start_ts"))
    .withColumn("band_end_ts", F.col("file_data_ts"))
    .withColumn("band_units", F.col("actual_units"))
    .withColumn("band_cost", F.col("actual_cost_calc"))
)

# ---------- ACTIVE -> REMAINING part ----------
active_rem = (
    base
    .filter(F.col("status_code") == "TK_Active")
    .withColumn("dist_type", F.lit("remaining"))
    .withColumn("band_source", F.lit("active_remaining"))
    .withColumn("band_start_ts", F.col("restart_ts"))
    .withColumn("band_end_ts", F.col("reend_ts"))
    .withColumn("band_units", F.col("remain_units"))
    .withColumn("band_cost", F.col("remain_cost_calc"))
)

bands = (
    not_started_rem
    .unionByName(complete_act, allowMissingColumns=True)
    .unionByName(active_act, allowMissingColumns=True)
    .unionByName(active_rem, allowMissingColumns=True)
)

# remove invalid bands
bands = bands.filter(
    F.col("band_start_ts").isNotNull() &
    F.col("band_end_ts").isNotNull() &
    (F.col("band_start_ts") <= F.col("band_end_ts")) &
    (F.coalesce(F.col("band_units"), F.lit(0.0)) > 0)
)

# =========================================================
# 6) Expand one row per band-day
# =========================================================
band_days = (
    bands
    .withColumn("band_start_date", F.to_date("band_start_ts"))
    .withColumn("band_end_date", F.to_date("band_end_ts"))
    .withColumn("Day", F.explode(F.sequence(F.col("band_start_date"), F.col("band_end_date"))))
    .withColumn("day_of_week", F.dayofweek("Day"))
    .withColumn("is_start_date", F.col("Day") == F.col("band_start_date"))
    .withColumn("is_end_date", F.col("Day") == F.col("band_end_date"))
    .withColumn("is_same_day", F.col("band_start_date") == F.col("band_end_date"))
    .withColumn("start_time", F.date_format("band_start_ts", "HH:mm"))
    .withColumn("end_time", F.date_format("band_end_ts", "HH:mm"))
)

# =========================================================
# 7) Join calendar and calculate precise daily work hours
# =========================================================
band_join = band_days.join(
    cal,
    on=["projcode", "clndr_id", "day_of_week"],
    how="left"
)

shift_start_cols = sorted(
    [c for c in cal.columns if c.startswith("shift") and c.endswith("_start")],
    key=lambda x: int(x.replace("shift", "").replace("_start", ""))
)

shift_finish_cols = sorted(
    [c for c in cal.columns if c.startswith("shift") and c.endswith("_finish")],
    key=lambda x: int(x.replace("shift", "").replace("_finish", ""))
)

total_expr = F.lit(0.0)

for s_col, f_col in zip(shift_start_cols, shift_finish_cols):
    s_sec = F.unix_timestamp(F.col(s_col), "HH:mm")
    f_sec = F.unix_timestamp(F.col(f_col), "HH:mm")

    middle_hours = (
        F.when(F.col(s_col).isNull() | F.col(f_col).isNull(), F.lit(0.0))
         .otherwise((f_sec - s_sec) / 3600.0)
    )

    same_day_hours = (
        F.when(F.col(s_col).isNull() | F.col(f_col).isNull(), F.lit(0.0))
         .otherwise(
             F.greatest(
                 F.lit(0.0),
                 (
                     F.least(F.unix_timestamp(F.col("end_time"), "HH:mm"), f_sec) -
                     F.greatest(F.unix_timestamp(F.col("start_time"), "HH:mm"), s_sec)
                 ) / 3600.0
             )
         )
    )

    start_day_hours = (
        F.when(F.col(s_col).isNull() | F.col(f_col).isNull(), F.lit(0.0))
         .otherwise(
             F.greatest(
                 F.lit(0.0),
                 (
                     f_sec -
                     F.greatest(F.unix_timestamp(F.col("start_time"), "HH:mm"), s_sec)
                 ) / 3600.0
             )
         )
    )

    end_day_hours = (
        F.when(F.col(s_col).isNull() | F.col(f_col).isNull(), F.lit(0.0))
         .otherwise(
             F.greatest(
                 F.lit(0.0),
                 (
                     F.least(F.unix_timestamp(F.col("end_time"), "HH:mm"), f_sec) - s_sec
                 ) / 3600.0
             )
         )
    )

    shift_hours = (
        F.when(F.col("is_same_day"), same_day_hours)
         .when(F.col("is_start_date"), start_day_hours)
         .when(F.col("is_end_date"), end_day_hours)
         .otherwise(middle_hours)
    )

    total_expr = total_expr + shift_hours

band_join = band_join.withColumn(
    "total_hours_worked_precise",
    F.round(total_expr, 4)
)

# =========================================================
# 8) Exception override
# =========================================================
band_join = band_join.join(
    exp_df,
    on=["projcode", "rev", "clndr_id", "Day"],
    how="left"
)

band_join = band_join.withColumn(
    "final_work_hours",
    F.coalesce(F.col("ex_total_shift_hours"), F.col("total_hours_worked_precise"), F.lit(0.0))
)

# =========================================================
# 9) Distribute units/cost by hour share within each band
# =========================================================
dist_keys = [
    "projcode", "rev", "task_id", "task_code", "clndr_id",
    "rsrc_id", "rsrc_name", "rsrc_short_name",
    "status_code", "dist_type", "band_source"
]

w = Window.partitionBy(*dist_keys)

dist_df = (
    band_join
    .withColumn("total_band_hours", F.sum("final_work_hours").over(w))
    .withColumn(
        "daily_hour_share",
        F.when(F.col("total_band_hours") > 0, F.col("final_work_hours") / F.col("total_band_hours"))
         .otherwise(F.lit(0.0))
    )
    .withColumn("daily_dist_units", F.round(F.col("daily_hour_share") * F.col("band_units"), 4))
    .withColumn("daily_dist_cost", F.round(F.col("daily_hour_share") * F.col("band_cost"), 4))
)

# =========================================================
# 10) Final combined table
# =========================================================
final_dist = dist_df.select(
    "projcode",
    "rev",
    "task_id",
    "task_code",
    "clndr_id",
    "rsrc_id",
    "rsrc_name",
    "rsrc_short_name",
    "status_code",
    "complete_pct_type",
    "phys_complete_pct",
    "pct_comp",
    "target_qty",
    "remain_qty",
    "actual_units",
    "remain_units",
    "target_cost",
    "actual_cost_calc",
    "remain_cost_calc",
    "dist_type",          # actual / remaining
    "band_source",        # not_started_early / complete_actual / active_actual / active_remaining
    "band_start_ts",
    "band_end_ts",
    "Day",
    "final_work_hours",
    "total_band_hours",
    "daily_hour_share",
    "daily_dist_units",
    "daily_dist_cost",
    "file_data_date",
    "early_start_date",
    "early_end_date",
    "act_start_date",
    "act_end_date",
    "restart_date",
    "reend_date"
)
# display(final_dist)


# In[36]:


from pyspark.sql.functions import lit, col

# ---- Option 2: align to existing Delta schema before write ----
target_table = "BL_Dist_Combined"

if spark.catalog.tableExists(target_table):
    target_fields = {f.name: f.dataType for f in spark.table(target_table).schema.fields}
    target_order  = spark.table(target_table).columns

    aligned_cols = []
    for c in target_order:
        if c in final_dist.columns:
            aligned_cols.append(final_dist[c].cast(target_fields[c]).alias(c))
        else:
            aligned_cols.append(lit(None).cast(target_fields[c]).alias(c))

    new_cols = [c for c in final_dist.columns if c not in target_fields]
    if new_cols:
        print(f"[{target_table}] new columns will be added via mergeSchema: {new_cols}")
        for c in new_cols:
            aligned_cols.append(final_dist[c])

    to_write = final_dist.select(*aligned_cols)
else:
    print(f"[{target_table}] table does not exist yet, creating from inferred schema.")
    to_write = final_dist

# ---- Write ----
(to_write.write
    .mode("append")
    .format("delta")
    .option("mergeSchema", "true")
    .option("delta.columnMapping.mode", "name")
    .option("delta.minReaderVersion", "2")
    .option("delta.minWriterVersion", "5")
    .saveAsTable(target_table))

# print(f"Wrote {target_table}")


# In[37]:


# from pyspark.sql.functions import col

# # List of table names to process
# tables = [
#     "task", "rsrc", "calendar", "actvcode", "taskactv", "taskpred",
#     "calendar_workdays", "calendar_exceptions", "bl_dist_combined",
#     "taskrsrc", "bl_remaining_dist", "bl_actuals_dists"
# ]

# projcode_val = "INFILLAF"

# for tbl in tables:
#     # Check if the table exists before running DELETE
#     if spark.catalog.tableExists(tbl):
#         spark.sql(f"DELETE FROM {tbl} WHERE projcode = '{projcode_val}'")
#         print(f"Deleted rows with projcode='{projcode_val}' from table '{tbl}'")
#     else:
#         print(f"Table '{tbl}' does not exist. Skipping.")

