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

package elfin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * ElFin on the 1802, editing a file, on your terminal.
 *
 * The editor, the tokenizer and the interpreter are one program here: the file
 * is tokenized as it is loaded and lives that way, and the editor detokenizes a
 * line at a time to paint it and to change it.
 *
 * Launch it through ../run-elfin.sh, which builds the image, puts the terminal
 * in raw mode and passes its size.  Ctrl-Q stops it - a convenience of running
 * on a host, not part of the language.
 */
public class RunElFin {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: RunElFin FILE [rows] [cols]   (run from the repository root)");
			System.exit(2);
		}
		Path file = Paths.get(args[0]);
		int rows = args.length > 1 ? Integer.parseInt(args[1]) : 24;
		int cols = args.length > 2 ? Integer.parseInt(args[2]) : 80;

		List<String> lines = new ArrayList<String>();
		if (Files.exists(file))
			for (String l : new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\n", -1))
				lines.add(l);
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
			lines.remove(lines.size() - 1);
		if (lines.isEmpty())
			lines.add("");

		Pcode p = new Pcode();
		p.call("RESET");
		for (String l : lines) {
			if (l.length() > ElFin.MAX_LINE)
				l = l.substring(0, ElFin.MAX_LINE);
			int at = ElFin.LINEBUF;
			for (int i = 0; i < l.length(); i++)
				p.poke(at + i, l.charAt(i));
			p.poke(at + l.length(), ElFin.EOL);
			p.set("SRC", at);
			p.call("TOKENIZE_LINE_OR_RAW");
			p.call("APPEND_LINE");
			if (p.get("ERR") != 0) {
				System.err.println("cannot load line [" + l + "]: error " + p.get("ERR"));
				System.exit(1);
			}
		}

		p.set("EROWS", rows);
		p.set("ECOLS", cols);
		p.set("ESCROLL", p.get("CODE_LO"));
		p.set("ETOP", 1);
		p.set("ECROW", 0);
		p.set("ECCOL", 0);
		p.set("EDIRTY", 0);
		p.set("EMOD", 0);
		p.call("ED_CURLINE");

		p.useTerminal();
		p.callForever("ED_MAIN");

		// Read the program back by detokenizing it, line by line, as the editor
		// itself does to paint one.
		p.call("GAP_CLOSE");
		StringBuilder text = new StringBuilder();
		p.set("PC", p.get("CODE_LO"));
		while (p.get("PC") < p.get("CODE_END")) {
			p.call("DETOK_LINE");
			for (int a = ElFin.LINEBUF; a < p.get("DSTP"); a++)
				text.append((char) p.peek(a));
			text.append('\n');
		}
		byte[] out = text.toString().getBytes(StandardCharsets.ISO_8859_1);
		byte[] before = Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
		if (java.util.Arrays.equals(before, out)) {
			System.err.println(file + ": unchanged");
			return;
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".new");
		Files.write(tmp, out);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		System.err.println(file + ": wrote " + out.length + " bytes (was " + before.length + ")");
	}
}
