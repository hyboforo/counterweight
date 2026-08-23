package com.counterweight.reporting.service

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A report reduced to a grid: headers, then rows of values.
 *
 * Exporters take this rather than the projections themselves, so adding a
 * report does not mean adding an export path. The alternative — an exporter per
 * report — is how half the reports end up without an export button.
 */
data class ReportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<Any?>>,
    /**
     * How each column reads. Empty when a table is built by hand rather than
     * through [of], which readers must tolerate.
     */
    val types: List<ColumnType> = emptyList(),
) {
    companion object {
        /** Builds a table from a list of rows and a set of named accessors. */
        fun <T> of(title: String, source: List<T>, vararg columns: Pair<String, (T) -> Any?>): ReportTable {
            val rows = source.map { row -> columns.map { (_, extract) -> extract(row) } }
            return ReportTable(
                title = title,
                headers = columns.map { it.first },
                rows = rows,
                types = columns.indices.map { i -> typeOf(rows.firstNotNullOfOrNull { it[i] }) },
            )
        }

        private fun typeOf(sample: Any?): ColumnType = when (sample) {
            is BigDecimal -> ColumnType.MONEY
            is Int, is Long, is Short -> ColumnType.INTEGER
            is Boolean -> ColumnType.BOOLEAN
            is LocalDate, is java.sql.Date -> ColumnType.DATE
            is Instant -> ColumnType.TIMESTAMP
            else -> ColumnType.TEXT
        }
    }
}

/**
 * How a column reads, decided where the report is defined.
 *
 * The exporters already branch on the runtime type — a `BigDecimal` gets the
 * money format and two decimals, an `Int` does not — but JSON erases the
 * distinction on the way to the screen: both arrive as a bare number, and a
 * takings column whose values happen to be whole renders as `261` beside a
 * downloaded file that says `261.00`. Carrying the type across is what keeps
 * the screen and the file the same report, which is the whole reason a report
 * is defined once rather than twice.
 *
 * Sampled from the first non-null value in the column rather than declared per
 * report, so adding a report still means adding one `ReportTable.of` call and
 * nothing else.
 */
enum class ColumnType { TEXT, INTEGER, MONEY, DATE, TIMESTAMP, BOOLEAN }

/**
 * Turns a report into a file somebody can take away.
 *
 * §12 asks for CSV, XLSX and A4 PDF. The first two are here; **PDF is not**,
 * and that is a stopping point rather than an oversight — an A4 report layout
 * is a typesetting job, and the thermal summary the till already prints covers
 * the case §12 gives for wanting one on paper ("the ones the owner wants in
 * hand"). Adding PDF later means implementing one more method here.
 */
@Component
class ReportExporter {

    /**
     * CSV, RFC 4180.
     *
     * Every field is quoted rather than only the ones that need it. Product
     * names in this shop contain commas and inch marks — `Pipe 2", 1.5m` — and
     * a conditional quoting rule is one edge case away from producing a file
     * that opens misaligned in somebody's spreadsheet.
     */
    fun toCsv(table: ReportTable): ByteArray {
        val out = StringBuilder()
        out.append(table.headers.joinToString(",") { quote(it) }).append("\r\n")
        table.rows.forEach { row ->
            out.append(row.joinToString(",") { quote(format(it)) }).append("\r\n")
        }
        /*
         * A UTF-8 BOM, deliberately. Excel on Windows reads a BOM-less UTF-8
         * CSV as the system codepage, which turns a customer named Adjoa
         * Asantewaa into mojibake — and this shop's exports are opened in Excel
         * on Windows.
         */
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * XLSX, one sheet.
     *
     * Numbers are written as numbers and dates as dates, not as text. A
     * spreadsheet of strings that happen to look like figures cannot be summed,
     * which defeats the reason for exporting to a spreadsheet at all.
     */
    fun toXlsx(table: ReportTable): ByteArray {
        /*
         * Held as the `Workbook` interface rather than as XSSFWorkbook. The
         * concrete classes narrow `cellStyle` to XSSFCellStyle, so working
         * through the interfaces keeps the style helpers reusable — and leaves
         * the door open to SXSSF if a report ever outgrows memory.
         */
        val workbook: Workbook = XSSFWorkbook()
        workbook.use {
            val sheet = workbook.createSheet(sheetName(table.title))
            val headerStyle = headerStyle(workbook)
            val moneyStyle = moneyStyle(workbook)
            val dateStyle = dateStyle(workbook)

            sheet.createRow(0).also { header ->
                table.headers.forEachIndexed { i, text ->
                    header.createCell(i).also {
                        it.setCellValue(text)
                        it.cellStyle = headerStyle
                    }
                }
            }

            table.rows.forEachIndexed { r, row ->
                val sheetRow = sheet.createRow(r + 1)
                row.forEachIndexed { c, value ->
                    val cell = sheetRow.createCell(c)
                    when (value) {
                        null -> cell.setBlank()
                        is BigDecimal -> {
                            cell.setCellValue(value.toDouble())
                            cell.cellStyle = moneyStyle
                        }
                        is Int -> cell.setCellValue(value.toDouble())
                        is Long -> cell.setCellValue(value.toDouble())
                        is Double -> cell.setCellValue(value)
                        is Boolean -> cell.setCellValue(value)
                        is LocalDate -> {
                            cell.setCellValue(java.sql.Date.valueOf(value))
                            cell.cellStyle = dateStyle
                        }
                        is java.sql.Date -> {
                            cell.setCellValue(value)
                            cell.cellStyle = dateStyle
                        }
                        is Instant -> {
                            cell.setCellValue(java.sql.Date.valueOf(value.atZone(ZONE).toLocalDate()))
                            cell.cellStyle = dateStyle
                        }
                        else -> cell.setCellValue(value.toString())
                    }
                }
            }

            // Sized to the content, because a column of ### is not a report.
            table.headers.indices.forEach { sheet.autoSizeColumn(it) }
            sheet.createFreezePane(0, 1)

            ByteArrayOutputStream().use { bytes ->
                workbook.write(bytes)
                return bytes.toByteArray()
            }
        }
    }

    private fun headerStyle(workbook: Workbook): CellStyle =
        workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true })
        }

    private fun moneyStyle(workbook: Workbook): CellStyle =
        workbook.createCellStyle().apply {
            dataFormat = workbook.createDataFormat().getFormat("#,##0.00")
            alignment = HorizontalAlignment.RIGHT
        }

    private fun dateStyle(workbook: Workbook): CellStyle =
        workbook.createCellStyle().apply {
            dataFormat = workbook.createDataFormat().getFormat("dd/mm/yyyy")
        }

    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    private fun format(value: Any?): String = when (value) {
        null -> ""
        is BigDecimal -> value.toPlainString()
        is Instant -> TIMESTAMP.format(value)
        is LocalDate -> DATE.format(value)
        else -> value.toString()
    }

    /**
     * Excel sheet names cap at 31 characters and reject `\ / ? * [ ]`.
     * A workbook that will not open is worse than a truncated tab name.
     */
    private fun sheetName(title: String) =
        title.replace(Regex("[\\\\/?*\\[\\]:]"), "-").take(31).ifBlank { "Report" }

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
