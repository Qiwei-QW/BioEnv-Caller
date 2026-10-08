package bioenv.core;

/**
 * One parameter's value as the user expressed it, together with whether they
 * expressed anything at all.
 *
 * <p>The distinction between "empty" and "untouched" is the whole point of this
 * type. A text field showing a hint is untouched; a field the user cleared
 * deliberately is also empty but was touched. Only a touched value belongs on
 * the command line (DESIGN.md P12).
 *
 * <p>Keeping the two apart prevents the failure this type was introduced to fix:
 * every option's help-stated default being written into the command line, so a
 * user who changed nothing still produced a twenty-parameter command containing
 * nonsense such as a boolean switch receiving the literal {@code false}.
 */
public final class ParamValue {

    /** The option this value belongs to. */
    public final Schema.Option option;
    /** Text as entered or selected; empty when nothing was expressed. */
    private String text = "";
    /** True once the user has edited or chosen this control. */
    private boolean touched;

    public ParamValue(Schema.Option option) {
        this.option = option;
    }

    public String text() {
        return text;
    }

    public boolean isTouched() {
        return touched;
    }

    /** Records an edit by the user. */
    public void set(String value) {
        this.text = value == null ? "" : value;
        this.touched = true;
    }

    /** Records a change that does not count as user intent (programmatic set). */
    public void setUntouched(String value) {
        this.text = value == null ? "" : value;
        this.touched = false;
    }

    /**
     * True when this value should appear on the command line.
     *
     * <p>The question is only whether the user expressed anything. Whether a value
     * is written after the flag is {@link CommandBuilder}'s decision, made from the
     * option's shape — not this class's.
     *
     * <p>This used to require the literal text {@code true} for a switch, because a
     * switch was a tick box and ticking it recorded that word. Every parameter is a
     * text field now, so a user who types anything at all into one has expressed
     * intent, and demanding a particular word silently discarded everything else:
     * typing "x" or "1" into a switch-shaped option contributed nothing, and if the
     * option had been misread as a switch, a path typed into it vanished too.
     */
    public boolean contributes() {
        return touched && !text.trim().isEmpty();
    }

    /** The text to place on the command line, trimmed. */
    public String commandText() {
        return text.trim();
    }

    @Override
    public String toString() {
        return (option == null ? "?" : option.flag) + "=" + text
                + (touched ? " (touched)" : " (untouched)");
    }
}
