package com.example.ui.fragment

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.AppNavigator
import com.example.R
import com.example.data.local.ItemConversionStatus
import com.example.data.local.WhatsAppSubpackEntity
import com.example.databinding.FragmentCollectionDetailBinding
import com.example.ui.adapter.LocalStickerAdapter
import com.example.ui.adapter.SubpackAdapter
import com.example.ui.viewmodel.CollectionDetailViewModel
import com.example.whatsapp.WhatsAppPackExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class CollectionDetailFragment : Fragment() {

    private var _binding: FragmentCollectionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollectionDetailViewModel by viewModels()

    private lateinit var subpackAdapter: SubpackAdapter
    private lateinit var stickerAdapter: LocalStickerAdapter
    private var pendingSubpackId: String? = null

    private val collectionId: String by lazy { requireArguments().getString(ARG_COLLECTION_ID).orEmpty() }

    private val whatsappLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val subpackId = pendingSubpackId
        pendingSubpackId = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (subpackId != null) {
                viewModel.markSubpackAdded(subpackId, true)
            }
            Snackbar.make(binding.root, R.string.whatsapp_add_confirmed, Snackbar.LENGTH_SHORT).show()
        } else {
            val errorMsg = result.data?.getStringExtra("validation_error")
            if (!errorMsg.isNullOrBlank()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.whatsapp_add_error, errorMsg),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                Snackbar.make(binding.root, R.string.whatsapp_add_cancelled, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subpackAdapter = SubpackAdapter { subpack ->
            exportSubpackToWhatsApp(subpack)
        }
        binding.rvWhatsappSubpacks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWhatsappSubpacks.adapter = subpackAdapter

        val spanCount = maxOf(3, resources.configuration.screenWidthDp / 115)
        stickerAdapter = LocalStickerAdapter()
        binding.rvLocalStickers.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.rvLocalStickers.adapter = stickerAdapter

        binding.btnEditCollection.setOnClickListener {
            val origin = viewModel.beginEditSession() ?: return@setOnClickListener
            (activity as? AppNavigator)?.openEditFromOrigin(origin)
        }

        binding.btnDeleteCollection.setOnClickListener {
            val colName = viewModel.details.value?.collection?.name ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_dialog_title, colName))
                .setMessage(R.string.delete_dialog_message)
                .setPositiveButton(R.string.confirm_delete) { _, _ ->
                    viewModel.deleteCollection {
                        parentFragmentManager.popBackStack()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        if (savedInstanceState == null) {
            viewModel.load(collectionId)
        }

        observeDetails()
    }

    private fun observeDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.details.collect { details ->
                    if (details == null) return@collect
                    val col = details.collection

                    (activity as? AppNavigator)?.configureToolbar(
                        title = col.name,
                        showBack = true,
                        showCreatePackAction = false
                    )

                    binding.tvDetailName.text = col.name
                    binding.tvDetailCounts.text = getString(
                        R.string.pack_card_counts,
                        col.totalCount,
                        col.animatedCount,
                        col.staticCount
                    )

                    val env = WhatsAppPackExporter.inspectEnvironment(requireContext())
                    val waStatus = getString(if (env.consumerInstalled) R.string.status_installed else R.string.status_not_installed)
                    val wbStatus = getString(if (env.businessInstalled) R.string.status_installed else R.string.status_not_installed)
                    binding.tvDetailWhatsappEnv.text = getString(
                        R.string.whatsapp_compatibility_status,
                        waStatus,
                        wbStatus
                    )

                    subpackAdapter.submitList(details.subpacks)
                    val completedStickers = details.items.filter { it.status == ItemConversionStatus.COMPLETED }
                    stickerAdapter.submitList(completedStickers)
                    binding.tvAllStickersHeader.text = getString(
                        R.string.collection_detail_stickers_title,
                        completedStickers.size
                    )
                }
            }
        }
    }

    private fun exportSubpackToWhatsApp(subpack: WhatsAppSubpackEntity) {
        val env = WhatsAppPackExporter.inspectEnvironment(requireContext())
        if (!env.anyInstalled) {
            Snackbar.make(binding.root, R.string.whatsapp_not_installed, Snackbar.LENGTH_LONG).show()
            return
        }
        if (!env.anyCompatible) {
            Snackbar.make(binding.root, R.string.whatsapp_incompatible, Snackbar.LENGTH_LONG).show()
            return
        }

        if (env.consumerCompatible && env.businessCompatible) {
            val options = arrayOf(
                getString(R.string.whatsapp_personal),
                getString(R.string.whatsapp_business)
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.whatsapp_choose_app)
                .setItems(options) { _, which ->
                    val targetPkg = if (which == 0) {
                        WhatsAppPackExporter.WHATSAPP_CONSUMER_PACKAGE
                    } else {
                        WhatsAppPackExporter.WHATSAPP_BUSINESS_PACKAGE
                    }
                    launchWhatsAppIntent(subpack, targetPkg)
                }
                .show()
        } else {
            val targetPkg = if (env.consumerCompatible) {
                WhatsAppPackExporter.WHATSAPP_CONSUMER_PACKAGE
            } else {
                WhatsAppPackExporter.WHATSAPP_BUSINESS_PACKAGE
            }
            launchWhatsAppIntent(subpack, targetPkg)
        }
    }

    private fun launchWhatsAppIntent(subpack: WhatsAppSubpackEntity, targetPkg: String) {
        try {
            pendingSubpackId = subpack.id
            val intent = WhatsAppPackExporter.buildAddToWhatsAppIntent(
                subpackId = subpack.id,
                subpackName = subpack.displayName,
                targetPackage = targetPkg
            )
            whatsappLauncher.launch(intent)
        } catch (e: Exception) {
            pendingSubpackId = null
            Snackbar.make(
                binding.root,
                getString(R.string.whatsapp_add_error, e.message ?: "Error"),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_COLLECTION_ID = "collection_id"

        fun newInstance(collectionId: String) = CollectionDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_COLLECTION_ID, collectionId)
            }
        }
    }
}
