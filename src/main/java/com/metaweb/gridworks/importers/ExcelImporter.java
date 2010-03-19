package com.metaweb.gridworks.importers;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang.NotImplementedException;
import org.apache.poi.common.usermodel.Hyperlink;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.metaweb.gridworks.model.Cell;
import com.metaweb.gridworks.model.Column;
import com.metaweb.gridworks.model.Project;
import com.metaweb.gridworks.model.Recon;
import com.metaweb.gridworks.model.ReconCandidate;
import com.metaweb.gridworks.model.Row;
import com.metaweb.gridworks.model.Recon.Judgment;

public class ExcelImporter implements Importer {
    final protected boolean _xmlBased;
    
    public ExcelImporter(boolean xmlBased) {
        _xmlBased = xmlBased;
    }

    public boolean takesReader() {
        return false;
    }
    
    public void read(Reader reader, Project project, Properties options, int skip, int limit)
            throws Exception {
        
        throw new NotImplementedException();
    }

    public void read(InputStream inputStream, Project project,
            Properties options, int skip, int limit) throws Exception {
        
        Workbook wb = null;
        try {
            wb = _xmlBased ? 
                new XSSFWorkbook(inputStream) : 
                new HSSFWorkbook(new POIFSFileSystem(inputStream));
        } catch (IOException e) {
            throw new IOException(
                "Attempted to parse file as Excel file but failed. " +
                "Try to use Excel to re-save the file as a different Excel version or as TSV and upload again.",
                e
            );
        }
        
        Sheet sheet = wb.getSheetAt(0);

        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        int r = firstRow;
        
        List<Integer>     nonBlankIndices = null;
        List<String>     nonBlankHeaderStrings = null;
        
        /*
         *  Find the header row
         */
        for (; r <= lastRow; r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            
            short firstCell = row.getFirstCellNum();
            short lastCell = row.getLastCellNum();
            if (firstCell >= 0 && firstCell <= lastCell) {
                nonBlankIndices = new ArrayList<Integer>(lastCell - firstCell + 1);
                nonBlankHeaderStrings = new ArrayList<String>(lastCell - firstCell + 1);
                
                for (int c = firstCell; c <= lastCell; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    if (cell != null) {
                        String text = cell.getStringCellValue().trim();
                        if (text.length() > 0) {
                            nonBlankIndices.add((int) c);
                            nonBlankHeaderStrings.add(text);
                        }
                    }
                }
                
                if (nonBlankIndices.size() > 0) {
                    r++;
                    break;
                }
            }
        }
        
        if (nonBlankIndices == null || nonBlankIndices.size() == 0) {
            return;
        }
        
        /*
         *  Create columns
         */
        for (int c = 0; c < nonBlankIndices.size(); c++) {
            Column column = new Column(c, nonBlankHeaderStrings.get(c));
            project.columnModel.columns.add(column);
        }
        
        /*
         *  Now process the data rows
         */
        int rowsWithData = 0;
        for (; r <= lastRow; r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            
            short firstCell = row.getFirstCellNum();
            short lastCell = row.getLastCellNum();
            if (firstCell >= 0 && firstCell <= lastCell) {
                Row newRow = new Row(nonBlankIndices.size());
                boolean hasData = false;
                
                for (int c = 0; c < nonBlankIndices.size(); c++) {
                    if (c < firstCell || c > lastCell) {
                        continue;
                    }
                    
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    if (cell == null) {
                        continue;
                    }
                    
                    int cellType = cell.getCellType();
                    if (cellType == org.apache.poi.ss.usermodel.Cell.CELL_TYPE_ERROR || 
                        cellType == org.apache.poi.ss.usermodel.Cell.CELL_TYPE_BLANK) {
                        continue;
                    }
                    if (cellType == org.apache.poi.ss.usermodel.Cell.CELL_TYPE_FORMULA) {
                        cellType = cell.getCachedFormulaResultType();
                    }
                    
                    Serializable value = null;
                    if (cellType == org.apache.poi.ss.usermodel.Cell.CELL_TYPE_BOOLEAN) {
                        value = cell.getBooleanCellValue();
                    } else if (cellType == org.apache.poi.ss.usermodel.Cell.CELL_TYPE_NUMERIC) {
                        value = cell.getNumericCellValue();
                    } else {
                        String text = cell.getStringCellValue().trim();
                        if (text.length() > 0) {
                            value = text;
                        }
                    }
                    
                    if (value != null) {
                        Recon recon = null;
                        
                        Hyperlink hyperlink = cell.getHyperlink();
                        if (hyperlink != null) {
                            String url = hyperlink.getAddress();
                            
                            if (url.startsWith("http://") || 
                                url.startsWith("https://")) {
                                
                                final String sig = "freebase.com/view";
                                
                                int i = url.indexOf(sig);
                                if (i > 0) {
                                    String id = url.substring(i + sig.length());
                                    
                                    int q = id.indexOf('?');
                                    if (q > 0) {
                                        id = id.substring(0, q);
                                    }
                                    int h = id.indexOf('#');
                                    if (h > 0) {
                                        id = id.substring(0, h);
                                    }
                                    
                                    recon = new Recon();
                                    recon.judgment = Judgment.Matched;
                                    recon.match = new ReconCandidate(id, "", value.toString(), new String[0], 100);
                                    recon.addCandidate(recon.match);
                                }
                            }
                        }
                        
                        newRow.setCell(c, new Cell(value, recon));
                        hasData = true;
                    }
                }
                
                if (hasData) {
                    rowsWithData++;
                    
                    if (skip <= 0 || rowsWithData > skip) {
                        project.rows.add(newRow);
                        project.columnModel.setMaxCellIndex(newRow.cells.size());
                        
                        if (limit > 0 && project.rows.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
