/*
    CuckooChess - A java fuberlin.offloadingchess.chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package fuberlin.offloadingchess.chess;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 *
 * @author petero
 */
public class PieceTest {

    public PieceTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    /**
     * Test of isWhite method, of class Piece.
     */
    @Test
    public void testIsWhite() {
        System.out.println("isWhite");
        assertEquals(false, Piece.isWhite(Piece.BBISHOP));
        assertEquals(true , Piece.isWhite(Piece.WBISHOP));
        assertEquals(true , Piece.isWhite(Piece.WKING));
        assertEquals(false, Piece.isWhite(Piece.BKING));
    }
}
