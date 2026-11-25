package com.chessmind.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChessBotScreen()
                }
            }
        }
    }
}

enum class Side { WHITE, BLACK; fun opposite() = if (this == WHITE) BLACK else WHITE }
enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }
data class Piece(val side: Side, val type: PieceType)
data class Move(val from: Int, val to: Int, val promotion: PieceType? = PieceType.QUEEN)

data class GameState(
    val board: Array<Piece?>,
    val whiteToMove: Boolean = true,
    val history: List<Array<Piece?>> = emptyList()
) {
    fun copyState(newBoard: Array<Piece?>, nextWhite: Boolean, saveHistory: Boolean = true): GameState {
        val nextHistory = if (saveHistory) history + board.clone() else history
        return GameState(newBoard, nextWhite, nextHistory)
    }
}

fun startBoard(): Array<Piece?> {
    val b = arrayOfNulls<Piece>(64)
    val backRank = listOf(
        PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
        PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
    )
    for (col in 0..7) {
        b[col] = Piece(Side.WHITE, backRank[col])
        b[8 + col] = Piece(Side.WHITE, PieceType.PAWN)
        b[48 + col] = Piece(Side.BLACK, PieceType.PAWN)
        b[56 + col] = Piece(Side.BLACK, backRank[col])
    }
    return b
}

fun parseFen(fen: String): GameState? {
    val parts = fen.trim().split(Regex("\\s+"))
    if (parts.isEmpty()) return null
    val rows = parts[0].split("/")
    if (rows.size != 8) return null
    val b = arrayOfNulls<Piece>(64)
    for (r in 0..7) {
        val fenRow = rows[7 - r]
        var col = 0
        for (ch in fenRow) {
            if (col >= 8) return null
            if (ch.isDigit()) {
                col += ch.digitToInt()
            } else {
                val side = if (ch.isUpperCase()) Side.WHITE else Side.BLACK
                val type = when (ch.lowercaseChar()) {
                    'p' -> PieceType.PAWN
                    'n' -> PieceType.KNIGHT
                    'b' -> PieceType.BISHOP
                    'r' -> PieceType.ROOK
                    'q' -> PieceType.QUEEN
                    'k' -> PieceType.KING
                    else -> return null
                }
                b[r * 8 + col] = Piece(side, type)
                col++
            }
        }
        if (col != 8) return null
    }
    val whiteTurn = if (parts.size >= 2) parts[1].lowercase() != "b" else true
    return GameState(b, whiteTurn)
}

fun toFen(state: GameState): String {
    val sb = StringBuilder()
    for (r in 7 downTo 0) {
        var empty = 0
        for (c in 0..7) {
            val p = state.board[r * 8 + c]
            if (p == null) {
                empty++
            } else {
                if (empty > 0) {
                    sb.append(empty)
                    empty = 0
                }
                val ch = when (p.type) {
                    PieceType.PAWN -> 'p'
                    PieceType.KNIGHT -> 'n'
                    PieceType.BISHOP -> 'b'
                    PieceType.ROOK -> 'r'
                    PieceType.QUEEN -> 'q'
                    PieceType.KING -> 'k'
                }
                sb.append(if (p.side == Side.WHITE) ch.uppercaseChar() else ch)
            }
        }
        if (empty > 0) sb.append(empty)
        if (r > 0) sb.append('/')
    }
    sb.append(' ').append(if (state.whiteToMove) 'w' else 'b').append(" - - 0 1")
    return sb.toString()
}

fun sqName(index: Int): String {
    val file = 'a' + (index % 8)
    val rank = '1' + (index / 8)
    return "$file$rank"
}

fun generateMoves(board: Array<Piece?>, side: Side): List<Move> {
    val moves = mutableListOf<Move>()
    for (idx in 0..63) {
        val piece = board[idx] ?: continue
        if (piece.side != side) continue
        val r = idx / 8
        val c = idx % 8

        when (piece.type) {
            PieceType.PAWN -> {
                val dir = if (side == Side.WHITE) 1 else -1
                val startRank = if (side == Side.WHITE) 1 else 6
                val promoRank = if (side == Side.WHITE) 7 else 0
                val nr = r + dir
                if (nr in 0..7) {
                    val fwd = nr * 8 + c
                    if (board[fwd] == null) {
                        if (nr == promoRank) {
                            moves.add(Move(idx, fwd, PieceType.QUEEN))
                        } else {
                            moves.add(Move(idx, fwd))
                            if (r == startRank) {
                                val fwd2 = (r + 2 * dir) * 8 + c
                                if (board[fwd2] == null) moves.add(Move(idx, fwd2))
                            }
                        }
                    }
                    for (dc in listOf(-1, 1)) {
                        val nc = c + dc
                        if (nc in 0..7) {
                            val cap = nr * 8 + nc
                            val target = board[cap]
                            if (target != null && target.side != side) {
                                moves.add(Move(idx, cap, if (nr == promoRank) PieceType.QUEEN else null))
                            }
                        }
                    }
                }
            }
            PieceType.KNIGHT -> {
                val jumps = listOf(
                    -2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1
                )
                for ((dr, dc) in jumps) {
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in 0..7 && nc in 0..7) {
                        val to = nr * 8 + nc
                        if (board[to]?.side != side) moves.add(Move(idx, to))
                    }
                }
            }
            PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN -> {
                val dirs = mutableListOf<Pair<Int, Int>>()
                if (piece.type == PieceType.BISHOP || piece.type == PieceType.QUEEN) {
                    dirs.addAll(listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1))
                }
                if (piece.type == PieceType.ROOK || piece.type == PieceType.QUEEN) {
                    dirs.addAll(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                }
                for ((dr, dc) in dirs) {
                    var nr = r + dr
                    var nc = c + dc
                    while (nr in 0..7 && nc in 0..7) {
                        val to = nr * 8 + nc
                        val target = board[to]
                        if (target == null) {
                            moves.add(Move(idx, to))
                        } else {
                            if (target.side != side) moves.add(Move(idx, to))
                            break
                        }
                        nr += dr
                        nc += dc
                    }
                }
            }
            PieceType.KING -> {
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0..7 && nc in 0..7) {
                            val to = nr * 8 + nc
                            if (board[to]?.side != side) moves.add(Move(idx, to))
                        }
                    }
                }
            }
        }
    }
    return moves
}

fun applyMove(board: Array<Piece?>, move: Move): Array<Piece?> {
    val next = board.clone()
    val moving = next[move.from]
    next[move.from] = null
    next[move.to] = if (moving != null && move.promotion != null) {
        Piece(moving.side, move.promotion)
    } else {
        moving
    }
    return next
}

fun evaluate(board: Array<Piece?>): Int {
    var score = 0
    for (p in board) {
        if (p == null) continue
        val v = when (p.type) {
            PieceType.PAWN -> 100
            PieceType.KNIGHT -> 320
            PieceType.BISHOP -> 330
            PieceType.ROOK -> 500
            PieceType.QUEEN -> 900
            PieceType.KING -> 20000
        }
        score += if (p.side == Side.WHITE) v else -v
    }
    return score
}

fun minimax(board: Array<Piece?>, depth: Int, alphaInit: Int, betaInit: Int, maximizing: Boolean): Int {
    if (depth == 0) return evaluate(board)
    val side = if (maximizing) Side.WHITE else Side.BLACK
    val moves = generateMoves(board, side)
    if (moves.isEmpty()) return if (maximizing) -50000 else 50000

    var alpha = alphaInit
    var beta = betaInit

    if (maximizing) {
        var best = Int.MIN_VALUE / 2
        for (m in moves) {
            val valScore = minimax(applyMove(board, m), depth - 1, alpha, beta, false)
            if (valScore > best) best = valScore
            if (valScore > alpha) alpha = valScore
            if (beta <= alpha) break
        }
        return best
    } else {
        var best = Int.MAX_VALUE / 2
        for (m in moves) {
            val valScore = minimax(applyMove(board, m), depth - 1, alpha, beta, true)
            if (valScore < best) best = valScore
            if (valScore < beta) beta = valScore
            if (beta <= alpha) break
        }
        return best
    }
}

fun findBestMove(state: GameState, depth: Int): String {
    val side = if (state.whiteToMove) Side.WHITE else Side.BLACK
    val moves = generateMoves(state.board, side)
    if (moves.isEmpty()) return "Žádné legální tahy"

    val maximizing = state.whiteToMove
    var bestMove = moves.first()
    var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE

    for (m in moves) {
        val nextBoard = applyMove(state.board, m)
        val score = minimax(nextBoard, depth - 1, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, !maximizing)
        if (maximizing && score > bestScore) {
            bestScore = score
            bestMove = m
        } else if (!maximizing && score < bestScore) {
            bestScore = score
            bestMove = m
        }
    }

    val evalText = if (abs(bestScore) > 15000) "Mat" else String.format("%.2f", bestScore / 100.0)
    return "Nejlepší tah: ${sqName(bestMove.from)} → ${sqName(bestMove.to)}  (Hodnocení: $evalText)"
}

@Composable
fun ChessBotScreen() {
    var gameState by remember { mutableStateOf(GameState(startBoard(), true)) }
    var selectedSq by remember { mutableStateOf<Int?>(null) }
    var fenText by remember { mutableStateOf(toFen(gameState)) }
    var statusText by remember { mutableStateOf("Vyber figurku nebo klikni na 'Spočítat tah'") }
    var isThinking by remember { mutableStateOf(false) }
    var depth by remember { mutableIntStateOf(3) }
    var flipped by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runBot() {
        isThinking = true
        statusText = "Počítám..."
        val snapshot = GameState(gameState.board.clone(), gameState.whiteToMove, emptyList())
        val d = depth
        scope.launch {
            val res = withContext(Dispatchers.Default) {
                findBestMove(snapshot, d)
            }
            statusText = res
            isThinking = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ChessMind AI Bot",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${if (gameState.whiteToMove) "Na tahu: Bílý" else "Na tahu: Černý"} • Hloubka: $depth",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val ranks = if (flipped) (0..7) else (7 downTo 0)
        val cols = if (flipped) (7 downTo 0) else (0..7)
        val activeSide = if (gameState.whiteToMove) Side.WHITE else Side.BLACK

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(2.dp, Color.Gray)
        ) {
            for (r in ranks) {
                Row(modifier = Modifier.weight(1f)) {
                    for (c in cols) {
                        val idx = r * 8 + c
                        val piece = gameState.board[idx]
                        val isLight = (r + c) % 2 != 0
                        val isSelected = selectedSq == idx
                        val bg = when {
                            isSelected -> Color(0xFFF6F669)
                            isLight -> Color(0xFFEEEED2)
                            else -> Color(0xFF769656)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(bg)
                                .clickable {
                                    val sel = selectedSq
                                    if (sel == null) {
                                        if (piece != null && piece.side == activeSide) {
                                            selectedSq = idx
                                        }
                                    } else if (sel == idx) {
                                        selectedSq = null
                                    } else {
                                        val legal = generateMoves(gameState.board, activeSide)
                                        val move = legal.firstOrNull { it.from == sel && it.to == idx }
                                        if (move != null) {
                                            val next = gameState.copyState(
                                                applyMove(gameState.board, move),
                                                !gameState.whiteToMove
                                            )
                                            gameState = next
                                            fenText = toFen(next)
                                            selectedSq = null
                                            runBot()
                                        } else {
                                            selectedSq = if (piece != null && piece.side == activeSide) idx else null
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pieceSymbol(piece),
                                fontSize = 28.sp,
                                color = Color.Black,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { runBot() },
                enabled = !isThinking,
                modifier = Modifier.weight(1f)
            ) {
                Text("Spočítat tah")
            }
            OutlinedButton(
                onClick = {
                    if (gameState.history.isNotEmpty()) {
                        val prevBoard = gameState.history.last()
                        val prevState = GameState(
                            prevBoard,
                            !gameState.whiteToMove,
                            gameState.history.dropLast(1)
                        )
                        gameState = prevState
                        fenText = toFen(prevState)
                        selectedSq = null
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zpět")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { flipped = !flipped },
                modifier = Modifier.weight(1f)
            ) {
                Text("Otočit")
            }
            OutlinedButton(
                onClick = { depth = if (depth >= 4) 2 else depth + 1 },
                modifier = Modifier.weight(1f)
            ) {
                Text("Síla: $depth")
            }
            OutlinedButton(
                onClick = {
                    val fresh = GameState(startBoard(), true)
                    gameState = fresh
                    fenText = toFen(fresh)
                    selectedSq = null
                    statusText = "Nová hra"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = fenText,
            onValueChange = { fenText = it },
            label = { Text("FEN pozice") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = {
                val parsed = parseFen(fenText)
                if (parsed != null) {
                    gameState = parsed
                    selectedSq = null
                    runBot()
                } else {
                    statusText = "Neplatný FEN"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Načíst FEN a najít tah")
        }
    }
}

fun pieceSymbol(piece: Piece?): String = when (piece) {
    Piece(Side.WHITE, PieceType.KING) -> "♔"
    Piece(Side.WHITE, PieceType.QUEEN) -> "♕"
    Piece(Side.WHITE, PieceType.ROOK) -> "♖"
    Piece(Side.WHITE, PieceType.BISHOP) -> "♗"
    Piece(Side.WHITE, PieceType.KNIGHT) -> "♘"
    Piece(Side.WHITE, PieceType.PAWN) -> "♙"
    Piece(Side.BLACK, PieceType.KING) -> "♚"
    Piece(Side.BLACK, PieceType.QUEEN) -> "♛"
   
