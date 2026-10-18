// ElFin - a programming language for the CDP1802
// Copyright (C) 2026 Kustaa Nyholm
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package cosmac;

import java.io.*;
import java.util.*;

public class IntelHexReader {
    
    /**
     * Reads an Intel HEX format file and returns the data as a byte array.
     * 
     * @param filePath Path to the Intel HEX file
     * @return Byte array containing the data from the HEX file
     * @throws IOException If file cannot be read
     * @throws IllegalArgumentException If file format is invalid
     */
    public static byte[] readIntelHexFile(String filePath) throws IOException {
        Map<Integer, Byte> memoryMap = new TreeMap<>();
        int maxAddress = 0;
        int extendedLinearAddress = 0;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || !line.startsWith(":")) {
                    continue;
                }
                
                try {
                    // Parse the record
                    int byteCount = Integer.parseInt(line.substring(1, 3), 16);
                    int address = Integer.parseInt(line.substring(3, 7), 16);
                    int recordType = Integer.parseInt(line.substring(7, 9), 16);
                    
                    // Verify checksum
                    if (!isValidChecksum(line)) {
                        throw new IllegalArgumentException("Invalid checksum on line " + lineNumber);
                    }
                    
                    // Process based on record type
                    switch (recordType) {
                        case 0x00: // Data record
                            int absoluteAddress = extendedLinearAddress + address;
                            for (int i = 0; i < byteCount; i++) {
                                int bytePos = 9 + (i * 2);
                                byte dataByte = (byte) Integer.parseInt(
                                    line.substring(bytePos, bytePos + 2), 16);
                                memoryMap.put(absoluteAddress + i, dataByte);
                                maxAddress = Math.max(maxAddress, absoluteAddress + i);
                            }
                            break;
                            
                        case 0x01: // End of file record
                            // Normal termination
                            break;
                            
                        case 0x02: // Extended segment address record
                            // Segment base address (for 16-bit segmented addressing)
                            extendedLinearAddress = 
                                Integer.parseInt(line.substring(9, 13), 16) * 16;
                            break;
                            
                        case 0x03: // Start segment address record
                            // Ignored - used for execution start address
                            break;
                            
                        case 0x04: // Extended linear address record
                            // Upper 16 bits of address
                            extendedLinearAddress = 
                                Integer.parseInt(line.substring(9, 13), 16) << 16;
                            break;
                            
                        case 0x05: // Start linear address record
                            // Ignored - used for execution start address
                            break;
                            
                        default:
                            throw new IllegalArgumentException(
                                "Unknown record type 0x" + Integer.toHexString(recordType) + 
                                " on line " + lineNumber);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                        "Invalid hex format on line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        
        // Convert map to byte array, filling gaps with 0xFF (unprogrammed memory)
        byte[] result = new byte[maxAddress + 1];
        Arrays.fill(result, (byte) 0xFF);
        
        for (Map.Entry<Integer, Byte> entry : memoryMap.entrySet()) {
            result[entry.getKey()] = entry.getValue();
        }
        
        return result;
    }
    
    /**
     * Validates the checksum of an Intel HEX record.
     * The checksum is the two's complement of the sum of all bytes in the record.
     * 
     * @param line The HEX record line
     * @return true if checksum is valid, false otherwise
     */
    private static boolean isValidChecksum(String line) {
        try {
            int sum = 0;
            // Sum all bytes from byte count through data/checksum
            for (int i = 1; i < line.length(); i += 2) {
                sum += Integer.parseInt(line.substring(i, i + 2), 16);
            }
            // Result should be 0 if checksum is valid (two's complement)
            return (sum & 0xFF) == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    // Example usage
    public static void main(String[] args) {
        try {
            byte[] data = readIntelHexFile("asm/pcode.hex");
            System.out.println("Successfully read " + data.length + " bytes");
            
            // Print first 16 bytes as example
            System.out.print("First 16 bytes: ");
            for (int i = 0; i < Math.min(16, data.length); i++) {
                System.out.printf("%02X ", data[i]);
            }
            System.out.println();
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("Format error: " + e.getMessage());
        }
    }
}
