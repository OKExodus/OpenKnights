package io.github.okexodus.openknights.patcher

/** Why the patcher stopped. Every case has its own code and a message written for players. */
enum class FailureCode(val exitCode: Int) {
    EMPTY_FOLDER(10),
    NO_GAME_FILE(11),
    WRONG_FILE_TYPE(12),
    DAMAGED_FILE(13),
    WRONG_GAME(14),
    UNSUPPORTED_VERSION(15),
    MODIFIED_GAME(16),
    INCOMPLETE_SPLITS(17),
    MISMATCHED_SPLITS(18),
    ENCRYPTED_FILE(19),
    SEVERAL_SUPPORTED(20),
    NO_SUPPORTED_FILE(21),
    INPUT_NOT_FOUND(22),
    PATCH_SITE_MISMATCH(30),
    NOT_ENOUGH_SPACE(40),
    OUTPUT_NOT_WRITABLE(41),
    SIGNING_KEY_PROBLEM(50),
    SIGNATURE_CHECK_FAILED(51),
    INTERNAL_ERROR(70),
}

/**
 * A failure the player can act on. [message] says what happened and what to do; [details] lists supporting facts
 * (for example which files were found and why each was not used).
 */
class PatchFailure(val code: FailureCode, message: String, val details: List<String> = emptyList(), cause: Throwable? = null) :
    Exception(message, cause)
