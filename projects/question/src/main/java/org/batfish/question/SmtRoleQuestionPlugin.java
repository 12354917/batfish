package org.batfish.question;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.batfish.common.Answerer;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.IQuestion;
import org.batfish.datamodel.questions.Question;
import org.batfish.datamodel.questions.smt.EquivalenceType;

public class SmtRoleQuestionPlugin extends QuestionPlugin {

  public static class RoleAnswerer extends Answerer {

    public RoleAnswerer(Question question, IBatfish batfish) {
      super(question, batfish);
    }

    @Override
    public AnswerElement answer() {
      RoleQuestion q = (RoleQuestion) _question;
      return _batfish.smtRoles(q.getType());
    }
  }

  public static class RoleQuestion extends Question implements IQuestion {

    private static final String PROP_EQUIVALENCE_TYPE = "equivType";

    private EquivalenceType _type;

    RoleQuestion() {
      _type = EquivalenceType.NODE;
    }

    @JsonProperty(PROP_EQUIVALENCE_TYPE)
    public EquivalenceType getType() {
      return _type;
    }

    @JsonProperty(PROP_EQUIVALENCE_TYPE)
    public void setType(EquivalenceType x) {
      this._type = x;
    }

    @Override
    public boolean getDataPlane() {
      return false;
    }

    @Override
    public String getName() {
      return "smt-roles";
    }

    @Override
    public boolean getTraffic() {
      return false;
    }
  }

  @Override
  protected Answerer createAnswerer(Question question, IBatfish batfish) {
    return new RoleAnswerer(question, batfish);
  }

  @Override
  protected Question createQuestion() {
    return new RoleQuestion();
  }

}
