package fi.hut.soberit.agilefant.business.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.support.PropertyComparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fi.hut.soberit.agilefant.business.AuthorizationBusiness;
import fi.hut.soberit.agilefant.business.HourEntryBusiness;
import fi.hut.soberit.agilefant.business.IterationBusiness;
import fi.hut.soberit.agilefant.business.ProductBusiness;
import fi.hut.soberit.agilefant.business.ProjectBusiness;
import fi.hut.soberit.agilefant.business.StoryBusiness;
import fi.hut.soberit.agilefant.business.TeamBusiness;
import fi.hut.soberit.agilefant.business.TransferObjectBusiness;
import fi.hut.soberit.agilefant.db.ProductDAO;
import fi.hut.soberit.agilefant.model.Backlog;
import fi.hut.soberit.agilefant.model.BacklogHourEntry;
import fi.hut.soberit.agilefant.model.Iteration;
import fi.hut.soberit.agilefant.model.Product;
import fi.hut.soberit.agilefant.model.ProductEntity;
import fi.hut.soberit.agilefant.model.Project;
import fi.hut.soberit.agilefant.model.Story;
import fi.hut.soberit.agilefant.model.Team;
import fi.hut.soberit.agilefant.security.SecurityUtil;
import fi.hut.soberit.agilefant.transfer.IterationTO;
import fi.hut.soberit.agilefant.transfer.LeafStoryContainer;
import fi.hut.soberit.agilefant.transfer.ProductTO;
import fi.hut.soberit.agilefant.transfer.ProjectTO;
import fi.hut.soberit.agilefant.transfer.Scheduled;
import fi.hut.soberit.agilefant.transfer.StoryTO;
import fi.hut.soberit.agilefant.util.Pair;
import fi.hut.soberit.agilefant.util.StoryComparator;

@Service("productBusiness")
@Transactional
public class ProductBusinessImpl extends GenericBusinessImpl<Product> implements ProductBusiness {

	private ProductDAO				productDAO;
	@Autowired
	private ProjectBusiness			projectBusiness;
	@Autowired
	private IterationBusiness		iterationBusiness;
	@Autowired
	private StoryBusiness			storyBusiness;
	@Autowired
	private HourEntryBusiness		hourEntryBusiness;
	@Autowired
	private TransferObjectBusiness	transferObjectBusiness;
	@Autowired
	private TeamBusiness			teamBusiness;
	@Autowired
	private AuthorizationBusiness	authorizationBusiness;

	protected SessionFactory		sessionFactory;

	public ProductBusinessImpl() {
		super(Product.class);
	}

	@Autowired
	public void setProductDAO(ProductDAO productDAO) {
		this.genericDAO = productDAO;
		this.productDAO = productDAO;
	}

	@Override
	@Transactional(readOnly = true)
	public Collection<Product> retrieveAllOrderByName() {
		return productDAO.retrieveBacklogTree();
	}

	@Override
	public Product store(int productId, Product productData, Set<Integer> teamIds) {
		// Method changed by Wilfred for SSDI to store the Product info in new
		// "products" table

		Session session = sessionFactory.getCurrentSession();

		this.validateProductData(productData);
		Product storable = new Product();
		if (productId > 0) {
			storable = this.retrieve(productId);
		}

		// Get teams
		Set<Team> teams = new HashSet<Team>();
		if (teamIds != null) {
			for (Integer tid : teamIds) {
				teams.add(teamBusiness.retrieve(tid));
			}
			storable.setTeams(teams);
		}

		storable.setName(productData.getName());
		storable.setDescription(productData.getDescription());
		if (storable.getId() > 0) {
			this.store(storable);
			return storable;
		} else {
			int createdId = this.create(storable);
			Product stored = new Product();
			stored = this.retrieve(createdId);

			ProductEntity productEntity = new ProductEntity();
			productEntity.setId(stored.getId());
			productEntity.setName(stored.getName());
			productEntity.setDescription(stored.getDescription());
			session.persist(productEntity);

			return stored;
		}
	}

	public void validateProductData(Product productData) throws IllegalArgumentException {
		if (productData.getName() == null || productData.getName().trim().length() == 0) {
			throw new IllegalArgumentException("product.emptyName");
		}
	}

	@Override
	public List<ProjectTO> retrieveProjects(Product product) {
		List<ProjectTO> projects = new ArrayList<ProjectTO>();
		for (Backlog child : product.getChildren()) {
			if (child instanceof Project) {
				projects.add(transferObjectBusiness.constructProjectTO((Project) child));
			}
		}
		return projects;
	}

	@Override
	public void delete(int id) {
		delete(retrieve(id));
	}

	@Override
	public void delete(Product product) {

		// Method changed by Wilfred for SSDI to delete the Project info from
		// new "projects" table

		if (product == null) {
			return;
		}
		Set<Backlog> children = new HashSet<Backlog>(product.getChildren());

		if (children != null) {
			for (Backlog item : children) {
				if (item instanceof Project) {
					projectBusiness.delete(item.getId());
				} else if (item instanceof Iteration) {
					iterationBusiness.delete(item.getId());
				}
			}
		}

		Set<Story> stories = new HashSet<Story>(product.getStories());
		if (stories != null) {
			for (Story item : stories) {
				storyBusiness.forceDelete(item);
			}
		}

		Set<BacklogHourEntry> hourEntries = new HashSet<BacklogHourEntry>(product.getHourEntries());
		if (hourEntries != null) {
			hourEntryBusiness.deleteAll(hourEntries);
		}

		Session session = sessionFactory.getCurrentSession();
		ProductEntity productEntity = (ProductEntity) session.get(ProductEntity.class, product.getId());
		if (productEntity != null) {
			session.delete(productEntity);
		}

		super.delete(product);

	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(readOnly = true)
	public ProductTO retrieveLeafStoriesOnly(Product product) {
		Map<Integer, LeafStoryContainer> backlogs = new HashMap<Integer, LeafStoryContainer>();

		ProductTO root = new ProductTO(product);
		root.setChildren(new HashSet<Backlog>());
		backlogs.put(root.getId(), root);

		createBacklogTo(product, backlogs, root);

		List<Story> stories = this.productDAO.retrieveLeafStories(product);

		for (Story story : stories) {
			final Iteration assignedIteration = story.getIteration();
			int backlog_id;

			if (assignedIteration != null && !assignedIteration.isStandAlone()) {
				backlog_id = assignedIteration.getId();
				backlogs.get(backlog_id).getLeafStories().add(new StoryTO(story));
			} else if (assignedIteration != null) {
				continue;
			} else {
				backlog_id = story.getBacklog().getId();
				backlogs.get(backlog_id).getLeafStories().add(new StoryTO(story));
			}

		}

		// sort backlogs
		Comparator<Scheduled> backlogComparator = new Comparator<Scheduled>() {
			private Comparator<Scheduled> inner = new PropertyComparator("startDate", true, false);

			@Override
			public int compare(Scheduled o1, Scheduled o2) {
				if (o1 == null) {
					return -1;
				}
				if (o2 == null) {
					return 1;
				}
				if (o1.getScheduleStatus() != o2.getScheduleStatus()) {
					if (o1.getScheduleStatus().ordinal() < o2.getScheduleStatus().ordinal()) {
						return 1;
					} else {
						return -1;
					}
				} else {
					return this.inner.compare(o1, o2);
				}
			}
		};

		for (ProjectTO project : root.getChildProjects()) {
			Collections.sort(project.getChildIterations(), backlogComparator);
		}
		Collections.sort(root.getChildProjects(), backlogComparator);

		// sort stories
		Comparator<Story> comparator = new StoryComparator();
		for (LeafStoryContainer container : backlogs.values()) {
			Collections.sort(container.getLeafStories(), comparator);
		}

		// Added standalone iterations
		List<Iteration> standAloneIterations = new ArrayList<Iteration>(
				iterationBusiness.retrieveAllStandAloneIterations());
		List<IterationTO> standIter = new ArrayList<IterationTO>();

		for (Iteration iter : standAloneIterations) {
			IterationTO iterTo = transferObjectBusiness.constructIterationTO(iter);

			// Added storyTo object as well
			List<Story> standAloneStories = storyBusiness.retrieveStoriesInIteration(iter);
			List<StoryTO> standAloneStoriesTo = new ArrayList<StoryTO>();
			for (Story s : standAloneStories) {
				standAloneStoriesTo.add(transferObjectBusiness.constructStoryTO(s));
			}

			iterTo.setRankedStories(standAloneStoriesTo);
			standIter.add(iterTo);
		}

		root.setStandaloneIterations(standIter);
		return root;
	}

	private void createBacklogTo(Backlog parent, Map<Integer, LeafStoryContainer> backlogs, Backlog parentTO) {
		List<Backlog> children = new ArrayList<Backlog>(parent.getChildren());
		for (Backlog child : children) {
			Backlog to = createTO(child, parentTO);
			to.setChildren(new HashSet<Backlog>());
			backlogs.put(to.getId(), (LeafStoryContainer) to);
			createBacklogTo(child, backlogs, to);
		}
	}

	private Backlog createTO(Backlog backlog, Backlog parentTO) {
		Backlog backlogTO = null;
		if (backlog instanceof Project) {
			backlogTO = new ProjectTO((Project) backlog);
			backlogTO.setParent(parentTO);
			((ProjectTO) backlogTO).setScheduleStatus(transferObjectBusiness.getBacklogScheduleStatus(backlog));
			((ProductTO) parentTO).getChildProjects().add((ProjectTO) backlogTO);
		} else if (backlog instanceof Iteration) {
			backlogTO = new IterationTO((Iteration) backlog);
			backlogTO.setParent(parentTO);
			((IterationTO) backlogTO).setScheduleStatus(transferObjectBusiness.getBacklogScheduleStatus(backlog));
			((ProjectTO) parentTO).getChildIterations().add((IterationTO) backlogTO);
		}
		return backlogTO;
	}

	@Override
	public Pair<DateTime, DateTime> calculateProductSchedule(Product product) {
		return productDAO.retrieveScheduleStartAndEnd(product);
	}

	@Override
	public void storeAllTimeSheets(Collection<Product> products) {
		Product standaloneProduct = new Product();
		standaloneProduct.setName("[Standalone Iterations]");
		standaloneProduct.setId(0);
		products.add(standaloneProduct);

		Collection<Product> canditateProducts = new ArrayList<Product>();

		canditateProducts.addAll(this.retrieveAll());

		// Make sure the user has sufficient rights to export timesheets.
		for (Iterator<Product> iter = canditateProducts.iterator(); iter.hasNext();) {
			Product product = iter.next();

			if (this.authorizationBusiness.isBacklogAccessible(product.getId(), SecurityUtil.getLoggedUser())) {
				products.add(product);
			}
		}
	}

	@Autowired
	public void setSessionFactory(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

}
