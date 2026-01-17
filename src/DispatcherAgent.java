import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;

import java.util.*;

public class DispatcherAgent extends Agent {
    private final Logger log = Logger.getMyLogger(getClass().getName());

    private final List<AID> technicians = new ArrayList<>();
    private int rr = 0;

    private final Deque<Ticket> queue = new ArrayDeque<>();
    private final Map<String, Ticket> pendingByConv = new HashMap<>();
    private final Map<String, Ticket> inFlightByConv = new HashMap<>();
    private final Map<String, Long> cooldownUntilByTech = new HashMap<>();

    private long lastQueueLogAt = 0L;

    private final int tickMs = 600;
    private final long optimisticCooldownMs = 300;
    private final long refuseCooldownMs = 1200;

    @Override
    protected void setup() {
        registerAsDispatcher();

        addBehaviour(new TickerBehaviour(this, tickMs) {
            @Override
            protected void onTick() {
                refreshTechnicians();
                tryDispatchOnce();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() == ACLMessage.REQUEST
                        && ServiceOntology.LANG.equals(msg.getLanguage())
                        && ServiceOntology.PROTOCOL_SUBMIT.equals(msg.getProtocol())) {
                    onClientRequest(msg);
                    return;
                }

                if (ServiceOntology.PROTOCOL_ASSIGN.equals(msg.getProtocol())) {
                    onTechnicianReply(msg);
                }
            }
        });

        log.info(getLocalName() + ": диспетчер запущен");
        refreshTechnicians();
        logQueue("старт системы");
    }

    private void onClientRequest(ACLMessage msg) {
        String issue = msg.getContent() == null ? "" : msg.getContent().trim();
        if (issue.isEmpty()) {
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
            reply.setLanguage(ServiceOntology.LANG);
            reply.setProtocol(ServiceOntology.PROTOCOL_RESULT);
            reply.setContent("Пустое описание заявки");
            send(reply);
            return;
        }

        Ticket t = new Ticket(UUID.randomUUID().toString(), msg.getSender(), issue);
        queue.addLast(t);

        ACLMessage ack = msg.createReply();
        ack.setPerformative(ACLMessage.AGREE);
        ack.setLanguage(ServiceOntology.LANG);
        ack.setProtocol(ServiceOntology.PROTOCOL_RESULT);
        ack.setContent("Заявка принята, id=" + t.ticketId + " (диспетчер=" + getLocalName() + ")");
        send(ack);

        logQueue("принята заявка " + t.ticketId);
        log.info(getLocalName() + ": принята заявка " + t.ticketId +
                " от " + msg.getSender().getLocalName() +
                " (проблема: " + issue + ")");

        tryDispatchOnce();
    }

    private void onTechnicianReply(ACLMessage msg) {
        String conv = msg.getConversationId();
        if (conv == null) return;

        Ticket t = inFlightByConv.get(conv);
        if (t == null) t = pendingByConv.get(conv);
        if (t == null) return;

        String techKey = msg.getSender().getName();
        int perf = msg.getPerformative();

        if (perf == ACLMessage.AGREE) {
            Ticket fromPending = pendingByConv.remove(conv);
            if (fromPending == null) return;
            inFlightByConv.put(conv, fromPending);
            logQueue("мастер принял " + fromPending.ticketId + " (" + msg.getSender().getLocalName() + ")");
            return;
        }

        if (perf == ACLMessage.REFUSE) {
            pendingByConv.remove(conv);
            inFlightByConv.remove(conv);

            cooldownUntilByTech.put(techKey, System.currentTimeMillis() + refuseCooldownMs);

            t.tried.add(techKey);
            queue.addLast(t);

            log.info(getLocalName() + ": мастер " + msg.getSender().getLocalName() +
                    " отказался (занят), заявка " + t.ticketId + " -> обратно в очередь");
            logQueue("REFUSE " + t.ticketId + " (" + msg.getSender().getLocalName() + ")");
            return;
        }

        if (perf == ACLMessage.INFORM) {
            pendingByConv.remove(conv);
            inFlightByConv.remove(conv);

            ACLMessage toClient = new ACLMessage(ACLMessage.CONFIRM);
            toClient.addReceiver(t.client);
            toClient.setLanguage(ServiceOntology.LANG);
            toClient.setProtocol(ServiceOntology.PROTOCOL_RESULT);
            toClient.setConversationId("result-" + t.ticketId);
            toClient.setContent(
                    "Заявка выполнена\n" +
                            "ID: " + t.ticketId + "\n" +
                            "Диспетчер: " + getLocalName() + "\n" +
                            "Мастер: " + msg.getSender().getLocalName() + "\n" +
                            "Результат: " + msg.getContent()
            );
            send(toClient);

            logQueue("выполнено " + t.ticketId + " (" + msg.getSender().getLocalName() + ")");
            return;
        }

        if (perf == ACLMessage.FAILURE) {
            pendingByConv.remove(conv);
            inFlightByConv.remove(conv);

            cooldownUntilByTech.put(techKey, System.currentTimeMillis() + refuseCooldownMs);

            t.tried.add(techKey);
            queue.addLast(t);

            logQueue("FAILURE " + t.ticketId + " (" + msg.getSender().getLocalName() + ")");
        }
    }

    private void tryDispatchOnce() {
        if (queue.isEmpty()) return;
        if (technicians.isEmpty()) return;

        Ticket t = queue.peekFirst();

        if (t.tried.size() >= technicians.size()) {
            t.tried.clear();
        }

        AID tech = pickTechnician(t);
        if (tech == null) {
            queue.removeFirst();
            queue.addLast(t);
            logQueue("нет доступных мастеров, заявка " + t.ticketId + " ждёт");
            return;
        }

        queue.removeFirst();

        String conv = "assign-" + t.ticketId + "-" + System.nanoTime();
        pendingByConv.put(conv, t);

        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(tech);
        req.setLanguage(ServiceOntology.LANG);
        req.setProtocol(ServiceOntology.PROTOCOL_ASSIGN);
        req.setConversationId(conv);
        req.setContent(
                "ID заявки: " + t.ticketId +
                        "\nПроблема: " + t.issue +
                        "\nКлиент: " + t.client.getLocalName() +
                        "\nДиспетчер: " + getLocalName()
        );

        cooldownUntilByTech.put(tech.getName(), System.currentTimeMillis() + optimisticCooldownMs);

        logQueue("назначение " + t.ticketId + " -> " + tech.getLocalName());
        send(req);
    }

    private AID pickTechnician(Ticket t) {
        long now = System.currentTimeMillis();
        int n = technicians.size();

        for (int k = 0; k < n; k++) {
            int idx = (rr + k) % n;
            AID cand = technicians.get(idx);
            String key = cand.getName();

            if (t.tried.contains(key)) continue;

            Long until = cooldownUntilByTech.get(key);
            if (until != null && until > now) continue;

            rr = (idx + 1) % n;
            return cand;
        }
        return null;
    }

    private void registerAsDispatcher() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_DISPATCHER);
            sd.setName("Диспетчер сервисного центра");
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new RuntimeException(e);
        }
    }

    private void refreshTechnicians() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(ServiceOntology.SERVICE_TECHNICIAN);
            template.addServices(sd);

            DFAgentDescription[] found = DFService.search(this, template);

            technicians.clear();
            for (DFAgentDescription d : found) technicians.add(d.getName());
            technicians.sort(Comparator.comparing(AID::getName));

            if (rr >= technicians.size()) rr = 0;

            Set<String> alive = new HashSet<>();
            for (AID a : technicians) alive.add(a.getName());
            cooldownUntilByTech.keySet().removeIf(k -> !alive.contains(k));

        } catch (FIPAException e) {
            technicians.clear();
            cooldownUntilByTech.clear();
            rr = 0;
        }
    }

    private void logQueue(String reason) {
        int inQueue = queue.size();
        int pending = pendingByConv.size();
        int inWork = inFlightByConv.size();

        long now = System.currentTimeMillis();
        if (now - lastQueueLogAt < 250) return;
        lastQueueLogAt = now;

        String r = (reason == null || reason.isBlank()) ? "" : (" | " + reason);
        log.info(getLocalName() + ": очередь -> в очереди: " + inQueue +
                ", pending: " + pending +
                ", в работе: " + inWork + r);
    }

    private static final class Ticket {
        final String ticketId;
        final AID client;
        final String issue;
        final Set<String> tried = new HashSet<>();

        Ticket(String ticketId, AID client, String issue) {
            this.ticketId = ticketId;
            this.client = client;
            this.issue = issue;
        }
    }
}
