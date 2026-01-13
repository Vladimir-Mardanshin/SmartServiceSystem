import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;

import java.util.Random;

public class TechnicianAgent extends Agent {
    private final Logger log = Logger.getMyLogger(getClass().getName());
    private final Random rnd = new Random();
    private volatile boolean busy = false;

    @Override
    protected void setup() {
        registerAsTechnician();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() != ACLMessage.REQUEST) return;

                if (busy) {
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setLanguage(ServiceOntology.LANG);
                    refuse.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);
                    refuse.setContent("Мастер занят");
                    send(refuse);

                    log.info(getLocalName() + ": отказ — занят");
                    return;
                }

                busy = true;

                log.info(getLocalName() + ": принял заявку, начинаю работу");

                int workMs = 1000 + rnd.nextInt(2000);
                boolean fail = rnd.nextDouble() < 0.15;

                addBehaviour(new OneShotBehaviour() {
                    @Override
                    public void action() {
                        try {
                            Thread.sleep(workMs);
                        } catch (InterruptedException ignored) {}

                        ACLMessage out = msg.createReply();
                        out.setLanguage(ServiceOntology.LANG);
                        out.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);

                        if (fail) {
                            out.setPerformative(ACLMessage.FAILURE);
                            out.setContent("Ошибка при диагностике устройства");
                            send(out);
                            log.info(getLocalName() + ": ошибка выполнения заявки");
                        } else {
                            out.setPerformative(ACLMessage.INFORM);
                            out.setContent("Работа завершена за " + workMs + " мс");
                            send(out);
                            log.info(getLocalName() + ": заявка успешно выполнена");
                        }

                        busy = false;
                    }
                });
            }
        });

        log.info(getLocalName() + ": мастер готов к работе");
    }

    private void registerAsTechnician() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_TECHNICIAN);
            sd.setName("Мастер сервисного центра");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new RuntimeException(e);
        }
    }
}
